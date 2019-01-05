// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toMap;

import com.google.common.base.Throwables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.ProjectRunnable;
import com.google.gerrit.server.git.WorkQueue.CanceledWhileRunning;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackend.RefFilterOptions;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.util.IdGenerator;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import com.jcraft.jsch.JSchException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.MDC;

/**
 * A push to remote operation started by {@link GitReferenceUpdatedListener}.
 *
 * <p>Instance members are protected by the lock within PushQueue. Callers must take that lock to
 * ensure they are working with a current view of the object.
 */
class PushOne implements ProjectRunnable, CanceledWhileRunning {
  private final ReplicationStateListener stateLog;
  static final String ALL_REFS = "..all..";
  static final String ID_MDC_KEY = "pushOneId";

  interface Factory {
    PushOne create(Project.NameKey d, URIish u);
  }

  private final GitRepositoryManager gitManager;
  private final PermissionBackend permissionBackend;
  private final Destination pool;
  private final RemoteConfig config;
  private final CredentialsProvider credentialsProvider;
  private final PerThreadRequestScope.Scoper threadScoper;

  private final Project.NameKey projectName;
  private final URIish uri;
  private final Set<String> delta = Sets.newHashSetWithExpectedSize(4);
  private boolean pushAllRefs;
  private Repository git;
  private boolean retrying;
  private int retryCount;
  private final int maxRetries;
  private boolean canceled;
  private final ListMultimap<String, ReplicationState> stateMap = LinkedListMultimap.create();
  private final int maxLockRetries;
  private int lockRetryCount;
  private final int id;
  private final long createdAt;
  private final ReplicationMetrics metrics;
  private final ProjectCache projectCache;
  private final CreateProjectTask.Factory createProjectFactory;
  private final AtomicBoolean canceledWhileRunning;

  @Inject
  PushOne(
      GitRepositoryManager grm,
      PermissionBackend permissionBackend,
      Destination p,
      RemoteConfig c,
      CredentialsFactory cpFactory,
      PerThreadRequestScope.Scoper ts,
      IdGenerator ig,
      ReplicationStateListener sl,
      ReplicationMetrics m,
      ProjectCache pc,
      CreateProjectTask.Factory cpf,
      @Assisted Project.NameKey d,
      @Assisted URIish u) {
    gitManager = grm;
    this.permissionBackend = permissionBackend;
    pool = p;
    config = c;
    credentialsProvider = cpFactory.create(c.getName());
    threadScoper = ts;
    projectName = d;
    uri = u;
    lockRetryCount = 0;
    maxLockRetries = pool.getLockErrorMaxRetries();
    id = ig.next();
    stateLog = sl;
    createdAt = System.nanoTime();
    metrics = m;
    projectCache = pc;
    createProjectFactory = cpf;
    canceledWhileRunning = new AtomicBoolean(false);
    maxRetries = p.getMaxRetries();
  }

  @Override
  public void cancel() {
    repLog.info("Replication {} was canceled", getURI());
    canceledByReplication();
    pool.pushWasCanceled(this);
  }

  @Override
  public void setCanceledWhileRunning() {
    repLog.info("Replication {} was canceled while being executed", getURI());
    canceledWhileRunning.set(true);
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return projectName;
  }

  @Override
  public String getRemoteName() {
    return config.getName();
  }

  @Override
  public boolean hasCustomizedPrint() {
    return true;
  }

  @Override
  public String toString() {
    String print = "[" + HexFormat.fromInt(id) + "] push " + uri;

    if (retryCount > 0) {
      print = "(retry " + retryCount + ") " + print;
    }
    return print;
  }

  boolean isRetrying() {
    return retrying;
  }

  boolean setToRetry() {
    retrying = true;
    retryCount++;
    return maxRetries == 0 || retryCount <= maxRetries;
  }

  void canceledByReplication() {
    canceled = true;
  }

  boolean wasCanceled() {
    return canceled;
  }

  URIish getURI() {
    return uri;
  }

  void addRef(String ref) {
    if (ALL_REFS.equals(ref)) {
      delta.clear();
      pushAllRefs = true;
      repLog.trace("Added all refs for replication to {}", uri);
    } else if (!pushAllRefs) {
      delta.add(ref);
      repLog.trace("Added ref {} for replication to {}", ref, uri);
    }
  }

  Set<String> getRefs() {
    return pushAllRefs ? Sets.newHashSet(ALL_REFS) : delta;
  }

  void addRefs(Set<String> refs) {
    if (!pushAllRefs) {
      for (String ref : refs) {
        addRef(ref);
      }
    }
  }

  void addState(String ref, ReplicationState state) {
    stateMap.put(ref, state);
  }

  ListMultimap<String, ReplicationState> getStates() {
    return stateMap;
  }

  ReplicationState[] getStatesAsArray() {
    Set<ReplicationState> statesSet = new HashSet<>();
    statesSet.addAll(stateMap.values());
    return statesSet.toArray(new ReplicationState[statesSet.size()]);
  }

  ReplicationState[] getStatesByRef(String ref) {
    Collection<ReplicationState> states = stateMap.get(ref);
    return states.toArray(new ReplicationState[states.size()]);
  }

  void addStates(ListMultimap<String, ReplicationState> states) {
    stateMap.putAll(states);
  }

  void removeStates() {
    stateMap.clear();
  }

  private void statesCleanUp() {
    if (!stateMap.isEmpty() && !isRetrying()) {
      for (Map.Entry<String, ReplicationState> entry : stateMap.entries()) {
        entry
            .getValue()
            .notifyRefReplicated(
                projectName.get(), entry.getKey(), uri, RefPushResult.FAILED, null);
      }
    }
  }

  @Override
  public void run() {
    try {
      threadScoper
          .scope(
              () -> {
                runPushOperation();
                return null;
              })
          .call();
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    } finally {
      statesCleanUp();
    }
  }

  private void runPushOperation() {
    // Lock the queue, and remove ourselves, so we can't be modified once
    // we start replication (instead a new instance, with the same URI, is
    // created and scheduled for a future point in time.)
    //
    MDC.put(ID_MDC_KEY, HexFormat.fromInt(id));
    if (!pool.requestRunway(this)) {
      if (!canceled) {
        repLog.info(
            "Rescheduling replication to {} to avoid collision with an in-flight push.", uri);
        pool.reschedule(this, Destination.RetryReason.COLLISION);
      }
      return;
    }

    repLog.info("Replication to {} started...", uri);
    Timer1.Context context = metrics.start(config.getName());
    try {
      long startedAt = context.getStartTime();
      long delay = NANOSECONDS.toMillis(startedAt - createdAt);
      metrics.record(config.getName(), delay, retryCount);
      git = gitManager.openRepository(projectName);
      runImpl();
      long elapsed = NANOSECONDS.toMillis(context.stop());
      repLog.info(
          "Replication to {} completed in {}ms, {}ms delay, {} retries",
          uri,
          elapsed,
          delay,
          retryCount);
    } catch (RepositoryNotFoundException e) {
      stateLog.error(
          "Cannot replicate " + projectName + "; Local repository error: " + e.getMessage(),
          getStatesAsArray());

    } catch (RemoteRepositoryException e) {
      // Tried to replicate to a remote via anonymous git:// but the repository
      // does not exist.  In this case NoRemoteRepositoryException is not
      // raised.
      String msg = e.getMessage();
      if (msg.contains("access denied")
          || msg.contains("no such repository")
          || msg.contains("Git repository not found")
          || msg.contains("unavailable")) {
        createRepository();
      } else {
        repLog.error("Cannot replicate {}; Remote repository error: {}", projectName, msg);
      }

    } catch (NoRemoteRepositoryException e) {
      createRepository();
    } catch (NotSupportedException e) {
      stateLog.error("Cannot replicate to " + uri, e, getStatesAsArray());
    } catch (TransportException e) {
      Throwable cause = e.getCause();
      if (cause instanceof JSchException && cause.getMessage().startsWith("UnknownHostKey:")) {
        repLog.error("Cannot replicate to {}: {}", uri, cause.getMessage());
      } else if (e instanceof LockFailureException) {
        lockRetryCount++;
        // The LockFailureException message contains both URI and reason
        // for this failure.
        repLog.error("Cannot replicate to {}: {}", uri, e.getMessage());

        // The remote push operation should be retried.
        if (lockRetryCount <= maxLockRetries) {
          if (canceledWhileRunning.get()) {
            logCanceledWhileRunningException(e);
          } else {
            pool.reschedule(this, Destination.RetryReason.TRANSPORT_ERROR);
          }
        } else {
          repLog.error(
              "Giving up after {} occurrences of this error: {} during replication to {}",
              lockRetryCount,
              e.getMessage(),
              uri);
        }
      } else {
        if (canceledWhileRunning.get()) {
          logCanceledWhileRunningException(e);
        } else {
          repLog.error("Cannot replicate to {}", uri, e);
          // The remote push operation should be retried.
          pool.reschedule(this, Destination.RetryReason.TRANSPORT_ERROR);
        }
      }
    } catch (IOException e) {
      stateLog.error("Cannot replicate to " + uri, e, getStatesAsArray());
    } catch (PermissionBackendException | RuntimeException | Error e) {
      stateLog.error("Unexpected error during replication to " + uri, e, getStatesAsArray());
    } finally {
      if (git != null) {
        git.close();
      }
      pool.notifyFinished(this);
    }
  }

  private void logCanceledWhileRunningException(TransportException e) {
    repLog.info("Cannot replicate to {}. It was canceled while running", uri, e);
  }

  private void createRepository() {
    if (pool.isCreateMissingRepos()) {
      try {
        Ref head = git.exactRef(Constants.HEAD);
        if (createProject(projectName, head != null ? getName(head) : null)) {
          repLog.warn("Missing repository created; retry replication to {}", uri);
          pool.reschedule(this, Destination.RetryReason.REPOSITORY_MISSING);
        } else {
          repLog.warn("Missing repository could not be created when replicating {}", uri);
        }
      } catch (IOException ioe) {
        stateLog.error(
            "Cannot replicate to " + uri + "; failed to create missing repository",
            ioe,
            getStatesAsArray());
      }
    } else {
      stateLog.error("Cannot replicate to " + uri + "; repository not found", getStatesAsArray());
    }
  }

  private boolean createProject(Project.NameKey project, String head) {
    return createProjectFactory.create(project, head).create();
  }

  private String getName(Ref ref) {
    Ref target = ref;
    while (target.isSymbolic()) {
      target = target.getTarget();
    }
    return target.getName();
  }

  private void runImpl() throws IOException, PermissionBackendException {
    PushResult res;
    try (Transport tn = Transport.open(git, uri)) {
      res = pushVia(tn);
    }
    updateStates(res.getRemoteUpdates());
  }

  private PushResult pushVia(Transport tn)
      throws IOException, NotSupportedException, TransportException, PermissionBackendException {
    tn.applyConfig(config);
    tn.setCredentialsProvider(credentialsProvider);

    List<RemoteRefUpdate> todo = generateUpdates(tn);
    if (todo.isEmpty()) {
      // If we have no commands selected, we have nothing to do.
      // Calling JGit at this point would just redo the work we
      // already did, and come up with the same answer. Instead
      // send back an empty result.
      return new PushResult();
    }

    repLog.info("Push to {} references: {}", uri, todo);

    return tn.push(NullProgressMonitor.INSTANCE, todo);
  }

  private List<RemoteRefUpdate> generateUpdates(Transport tn)
      throws IOException, PermissionBackendException {
    ProjectState projectState = projectCache.checkedGet(projectName);
    if (projectState == null) {
      return Collections.emptyList();
    }

    Map<String, Ref> local =
        git.getRefDatabase().getRefs().stream().collect(toMap(Ref::getName, r -> r));
    boolean filter;
    PermissionBackend.ForProject forProject = permissionBackend.currentUser().project(projectName);
    try {
      projectState.checkStatePermitsRead();
      forProject.check(ProjectPermission.READ);
      filter = false;
    } catch (AuthException | ResourceConflictException e) {
      filter = true;
    }
    if (filter) {
      if (!pushAllRefs) {
        // If we aren't mirroring, reduce the space we need to filter
        // to only the references we will update during this operation.
        //
        Map<String, Ref> n = new HashMap<>();
        for (String src : delta) {
          Ref r = local.get(src);
          if (r != null) {
            n.put(src, r);
          }
        }
        local = n;
      }
      local = forProject.filter(local, git, RefFilterOptions.builder().setFilterMeta(true).build());
    }

    return pushAllRefs ? doPushAll(tn, local) : doPushDelta(local);
  }

  private List<RemoteRefUpdate> doPushAll(Transport tn, Map<String, Ref> local)
      throws NotSupportedException, TransportException, IOException {
    List<RemoteRefUpdate> cmds = new ArrayList<>();
    boolean noPerms = !pool.isReplicatePermissions();
    Map<String, Ref> remote = listRemote(tn);
    for (Ref src : local.values()) {
      if (!canPushRef(src.getName(), noPerms)) {
        continue;
      }

      RefSpec spec = matchSrc(src.getName());
      if (spec != null) {
        Ref dst = remote.get(spec.getDestination());
        if (dst == null || !src.getObjectId().equals(dst.getObjectId())) {
          // Doesn't exist yet, or isn't the same value, request to push.
          push(cmds, spec, src);
        }
      }
    }

    if (config.isMirror()) {
      for (Ref ref : remote.values()) {
        if (!Constants.HEAD.equals(ref.getName())) {
          RefSpec spec = matchDst(ref.getName());
          if (spec != null && !local.containsKey(spec.getSource())) {
            // No longer on local side, request removal.
            delete(cmds, spec);
          }
        }
      }
    }
    return cmds;
  }

  private List<RemoteRefUpdate> doPushDelta(Map<String, Ref> local) throws IOException {
    List<RemoteRefUpdate> cmds = new ArrayList<>();
    boolean noPerms = !pool.isReplicatePermissions();
    for (String src : delta) {
      RefSpec spec = matchSrc(src);
      if (spec != null) {
        // If the ref still exists locally, send it, otherwise delete it.
        Ref srcRef = local.get(src);

        // Second try to ensure that the ref is truly not found locally
        if (srcRef == null) {
          srcRef = git.exactRef(src);
        }

        if (srcRef != null && canPushRef(src, noPerms)) {
          push(cmds, spec, srcRef);
        } else if (config.isMirror()) {
          delete(cmds, spec);
        }
      }
    }
    return cmds;
  }

  private boolean canPushRef(String ref, boolean noPerms) {
    return !(noPerms && RefNames.REFS_CONFIG.equals(ref))
        && !ref.startsWith(RefNames.REFS_CACHE_AUTOMERGE);
  }

  private Map<String, Ref> listRemote(Transport tn)
      throws NotSupportedException, TransportException {
    try (FetchConnection fc = tn.openFetch()) {
      return fc.getRefsMap();
    }
  }

  private RefSpec matchSrc(String ref) {
    for (RefSpec s : config.getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return s.expandFromSource(ref);
      }
    }
    return null;
  }

  private RefSpec matchDst(String ref) {
    for (RefSpec s : config.getPushRefSpecs()) {
      if (s.matchDestination(ref)) {
        return s.expandFromDestination(ref);
      }
    }
    return null;
  }

  private void push(List<RemoteRefUpdate> cmds, RefSpec spec, Ref src) throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, src, dst, force, null, null));
  }

  private void delete(List<RemoteRefUpdate> cmds, RefSpec spec) throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, (Ref) null, dst, force, null, null));
  }

  private void updateStates(Collection<RemoteRefUpdate> refUpdates) throws LockFailureException {
    Set<String> doneRefs = new HashSet<>();
    boolean anyRefFailed = false;
    RemoteRefUpdate.Status lastRefStatusError = RemoteRefUpdate.Status.OK;

    for (RemoteRefUpdate u : refUpdates) {
      RefPushResult pushStatus = RefPushResult.SUCCEEDED;
      Set<ReplicationState> logStates = new HashSet<>();

      logStates.addAll(stateMap.get(u.getSrcRef()));
      logStates.addAll(stateMap.get(ALL_REFS));
      ReplicationState[] logStatesArray = logStates.toArray(new ReplicationState[logStates.size()]);

      doneRefs.add(u.getSrcRef());
      switch (u.getStatus()) {
        case OK:
        case UP_TO_DATE:
        case NON_EXISTING:
          break;

        case NOT_ATTEMPTED:
        case AWAITING_REPORT:
        case REJECTED_NODELETE:
        case REJECTED_NONFASTFORWARD:
        case REJECTED_REMOTE_CHANGED:
          stateLog.error(
              String.format(
                  "Failed replicate of %s to %s: status %s", u.getRemoteName(), uri, u.getStatus()),
              logStatesArray);
          pushStatus = RefPushResult.FAILED;
          anyRefFailed = true;
          lastRefStatusError = u.getStatus();
          break;

        case REJECTED_OTHER_REASON:
          if ("non-fast-forward".equals(u.getMessage())) {
            stateLog.error(
                String.format(
                    "Failed replicate of %s to %s"
                        + ", remote rejected non-fast-forward push."
                        + "  Check receive.denyNonFastForwards variable in config file"
                        + " of destination repository.",
                    u.getRemoteName(), uri),
                logStatesArray);
          } else if ("failed to lock".equals(u.getMessage())) {
            throw new LockFailureException(uri, u.getMessage());
          } else {
            stateLog.error(
                String.format(
                    "Failed replicate of %s to %s, reason: %s",
                    u.getRemoteName(), uri, u.getMessage()),
                logStatesArray);
          }
          pushStatus = RefPushResult.FAILED;
          anyRefFailed = true;
          lastRefStatusError = u.getStatus();
          break;
      }

      for (ReplicationState rs : getStatesByRef(u.getSrcRef())) {
        rs.notifyRefReplicated(projectName.get(), u.getSrcRef(), uri, pushStatus, u.getStatus());
      }
    }

    doneRefs.add(ALL_REFS);
    for (ReplicationState rs : getStatesByRef(ALL_REFS)) {
      rs.notifyRefReplicated(
          projectName.get(),
          ALL_REFS,
          uri,
          anyRefFailed ? RefPushResult.FAILED : RefPushResult.SUCCEEDED,
          lastRefStatusError);
    }
    for (Map.Entry<String, ReplicationState> entry : stateMap.entries()) {
      if (!doneRefs.contains(entry.getKey())) {
        entry
            .getValue()
            .notifyRefReplicated(
                projectName.get(), entry.getKey(), uri, RefPushResult.NOT_ATTEMPTED, null);
      }
    }
    stateMap.clear();
  }

  public static class LockFailureException extends TransportException {
    private static final long serialVersionUID = 1L;

    LockFailureException(URIish uri, String message) {
      super(uri, message);
    }
  }
}
