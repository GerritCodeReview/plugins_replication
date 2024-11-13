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

import static com.google.common.flogger.LazyArgs.lazy;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.ProjectRunnable;
import com.google.gerrit.server.git.WorkQueue.CanceledWhileRunning;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.logging.TraceContext;
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
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.api.ReplicationPushFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

/**
 * A push to remote operation started by {@link GitBatchRefUpdateListener}.
 *
 * <p>Instance members are protected by the lock within PushQueue. Callers must take that lock to
 * ensure they are working with a current view of the object.
 */
class PushOne implements ProjectRunnable, CanceledWhileRunning, UriUpdates {
  private final ReplicationStateListener stateLog;
  static final String ALL_REFS = "..all..";
  static final String ID_KEY = "pushOneId";

  // The string here needs to match the one returned by Git(versions prior to 2014) server.
  // See:
  // https://github.com/git/git/blob/b4d75ac1d152bbab44b0777a4cc0c48db75f6024/builtin/receive-pack.c#L587
  // https://github.com/eclipse/jgit/blob/8774f541904ca9afba1786b4da14c1aedf4dda78/org.eclipse.jgit/src/org/eclipse/jgit/transport/ReceivePack.java#L1859
  static final String LOCK_FAILURE = "failed to lock";

  // The string here needs to match the one returned by Git server.
  // See:
  // https://github.com/git/git/blob/e67fbf927dfdf13d0b21dc6ea15dc3c7ef448ea0/builtin/receive-pack.c#L1611
  static final String UPDATE_REF_FAILURE = "failed to update ref";

  interface Factory {
    PushOne create(Project.NameKey d, URIish u);
  }

  private final GitRepositoryManager gitManager;
  private final PermissionBackend permissionBackend;
  private final Destination pool;
  private final RemoteConfig config;
  private final ReplicationConfig replConfig;
  private final CredentialsFactory credentialsFactory;
  private final PerThreadRequestScope.Scoper threadScoper;

  private final Project.NameKey projectName;
  private final URIish uri;
  private final Set<ImmutableSet<String>> refBatchesToPush = Sets.newConcurrentHashSet();
  private boolean pushAllRefs;
  private Repository git;
  private boolean isCollision;
  private boolean retrying;
  private int retryCount;
  private final int maxRetries;
  private boolean canceled;
  private final ListMultimap<String, ReplicationState> stateMap = LinkedListMultimap.create();
  private final int maxUpdateRefRetries;
  private int updateRefRetryCount;
  private final int id;
  private final long createdAt;
  private final ReplicationMetrics metrics;
  private final ProjectCache projectCache;
  private final CreateProjectTask.Factory createProjectFactory;
  private final AtomicBoolean canceledWhileRunning;
  private final TransportFactory transportFactory;
  private DynamicItem<ReplicationPushFilter> replicationPushFilter;

  @Inject
  PushOne(
      GitRepositoryManager grm,
      PermissionBackend permissionBackend,
      Destination p,
      RemoteConfig c,
      ReplicationConfig rc,
      CredentialsFactory cpFactory,
      PerThreadRequestScope.Scoper ts,
      IdGenerator ig,
      ReplicationStateListeners sl,
      ReplicationMetrics m,
      ProjectCache pc,
      CreateProjectTask.Factory cpf,
      TransportFactory tf,
      @Assisted Project.NameKey d,
      @Assisted URIish u) {
    gitManager = grm;
    this.permissionBackend = permissionBackend;
    pool = p;
    config = c;
    replConfig = rc;
    credentialsFactory = cpFactory;
    threadScoper = ts;
    projectName = d;
    uri = u;
    updateRefRetryCount = 0;
    maxUpdateRefRetries = pool.getUpdateRefErrorMaxRetries();
    id = ig.next();
    stateLog = sl;
    createdAt = System.nanoTime();
    metrics = m;
    projectCache = pc;
    createProjectFactory = cpf;
    canceledWhileRunning = new AtomicBoolean(false);
    maxRetries = p.getMaxRetries();
    transportFactory = tf;
  }

  @Inject(optional = true)
  public void setReplicationPushFilter(DynamicItem<ReplicationPushFilter> replicationPushFilter) {
    this.replicationPushFilter = replicationPushFilter;
  }

  @Override
  public void cancel() {
    repLog.atInfo().log("Replication [%s] to %s was canceled", HexFormat.fromInt(id), getURI());
    canceledByReplication();
    pool.pushWasCanceled(this);
  }

  @Override
  public void setCanceledWhileRunning() {
    repLog.atInfo().log(
        "Replication [%s] to %s was canceled while being executed",
        HexFormat.fromInt(id), getURI());
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
    String print = "[" + HexFormat.fromInt(id) + "] push " + uri + " " + getLimitedRefs();

    if (retryCount > 0) {
      print = "(retry " + retryCount + ") " + print;
    }
    return print;
  }

  /**
   * Returns a string of refs limited to the maxRefsToShow config with count of total refs hidden
   * when there are more refs than maxRefsToShow config.
   *
   * <ul>
   *   <li>Refs will not be limited when maxRefsToShow config is set to zero.
   *   <li>By default output will be limited to two refs.
   * </ul>
   *
   * The default value of two is chosen because whenever a new patchset is created there are two
   * refs to be replicated(change ref and meta ref).
   *
   * @return Space separated string of refs (in square bracket) limited to the maxRefsToShow with
   *     count of total refs hidden(in parentheses) when there are more refs than maxRefsToShow
   *     config.
   */
  protected String getLimitedRefs() {
    Set<ImmutableSet<String>> refs = getRefs();
    int maxRefsToShow = replConfig.getMaxRefsToShow();

    long totalRefs = refs.stream().mapToInt(Set::size).sum();
    Stream<String> refsStream = refs.stream().flatMap(Collection::stream);

    Stream<String> refsFiltered =
        (maxRefsToShow == 0) ? refsStream : refsStream.limit(maxRefsToShow);

    String refsString = refsFiltered.collect(Collectors.joining(" "));

    if (maxRefsToShow > 0 && totalRefs > maxRefsToShow) {
      int hiddenRefs = (int) (totalRefs - maxRefsToShow);
      refsString += " (+" + hiddenRefs + ")";
    }
    return "[" + refsString + "]";
  }

  boolean isRetrying() {
    return retrying;
  }

  boolean setToRetry() {
    retrying = true;
    retryCount++;
    return maxRetries == 0 || retryCount <= maxRetries;
  }

  void retryDone() {
    this.retrying = false;
  }

  void canceledByReplication() {
    canceled = true;
  }

  boolean wasCanceled() {
    return canceled || canceledWhileRunning.get();
  }

  @Override
  public URIish getURI() {
    return uri;
  }

  /** Returns false if all refs were already included in the push, true otherwise */
  boolean addRefBatch(ImmutableSet<String> refBatch) {
    if (refBatch.size() == 1 && refBatch.contains(ALL_REFS)) {
      refBatchesToPush.clear();
      boolean pushAllRefsChanged = !pushAllRefs;
      pushAllRefs = true;
      repLog.atFinest().log("Added all refs for replication to %s", uri);
      return pushAllRefsChanged;
    }
    if (!pushAllRefs && refBatchesToPush.add(refBatch)) {
      repLog.atFinest().log("Added ref %s for replication to %s", refBatch, uri);
      return true;
    }
    return false;
  }

  @Override
  public Set<ImmutableSet<String>> getRefs() {
    return pushAllRefs ? Set.of(ImmutableSet.of(ALL_REFS)) : refBatchesToPush;
  }

  void addRefBatches(Set<ImmutableSet<String>> refBatches) {
    if (!pushAllRefs) {
      for (ImmutableSet<String> refBatch : refBatches) {
        addRefBatch(refBatch);
      }
    }
  }

  Set<ImmutableSet<String>> setStartedRefs(Set<ImmutableSet<String>> startedRefs) {
    Set<ImmutableSet<String>> notAttemptedRefs = Sets.difference(refBatchesToPush, startedRefs);
    pushAllRefs = false;
    refBatchesToPush.clear();
    addRefBatches(startedRefs);
    return notAttemptedRefs;
  }

  void notifyNotAttempted(Set<ImmutableSet<String>> notAttemptedRefs) {
    notAttemptedRefs.stream()
        .flatMap(Collection::stream)
        .forEach(
            ref ->
                Arrays.asList(getStatesByRef(ref))
                    .forEach(
                        state ->
                            state.notifyRefReplicated(
                                projectName.get(),
                                ref,
                                uri,
                                RefPushResult.NOT_ATTEMPTED,
                                RemoteRefUpdate.Status.UP_TO_DATE)));
  }

  void addState(Set<String> refs, ReplicationState state) {
    for (String ref : refs) {
      stateMap.put(ref, state);
    }
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
    List<ReplicationState> states = stateMap.get(ref);
    return states.toArray(new ReplicationState[states.size()]);
  }

  public int getId() {
    return id;
  }

  void addStates(ListMultimap<String, ReplicationState> states) {
    stateMap.putAll(states);
  }

  void removeStates() {
    stateMap.clear();
  }

  private void statesCleanUp() {
    if (!stateMap.isEmpty() && !isRetrying() && !isCollision) {
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
    try (TraceContext ctx = TraceContext.open().addTag(ID_KEY, HexFormat.fromInt(id))) {
      doRunPushOperation();
    }
  }

  private void doRunPushOperation() {
    // Lock the queue, and remove ourselves, so we can't be modified once
    // we start replication (instead a new instance, with the same URI, is
    // created and scheduled for a future point in time.)
    //
    RunwayStatus status = pool.requestRunway(this);
    isCollision = false;
    if (!status.isAllowed()) {
      if (status.isCanceled()) {
        repLog.atInfo().log(
            "PushOp for replication to %s was canceled and thus won't be rescheduled", uri);
      } else {
        repLog.atInfo().log(
            "Rescheduling replication to %s to avoid collision with the in-flight push [%s].",
            uri, HexFormat.fromInt(status.getInFlightPushId()));
        pool.reschedule(this, Destination.RetryReason.COLLISION);
        isCollision = true;
      }
      return;
    }

    repLog.atInfo().log("Replication to %s started...", uri);
    Timer1.Context<String> destinationContext = metrics.start(config.getName());
    try {
      long startedAt = destinationContext.getStartTime();
      long delay = NANOSECONDS.toMillis(startedAt - createdAt);
      metrics.record(config.getName(), delay, retryCount);
      git = gitManager.openRepository(projectName);
      runImpl();
      long elapsed = NANOSECONDS.toMillis(destinationContext.stop());

      if (elapsed > SECONDS.toMillis(pool.getSlowLatencyThreshold())) {
        metrics.recordSlowProjectReplication(
            config.getName(), projectName.get(), pool.getSlowLatencyThreshold(), elapsed);
      }
      retryDone();
      repLog.atInfo().log(
          "Replication to %s completed in %dms, %dms delay, %d retries",
          uri, elapsed, delay, retryCount);
    } catch (RepositoryNotFoundException e) {
      retryDone();
      stateLog.error(
          "Cannot replicate "
              + projectName
              + "; Local repository does not exist: "
              + e.getMessage(),
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
        repLog.atSevere().log("Cannot replicate %s; Remote repository error: %s", projectName, msg);
      }

    } catch (NoRemoteRepositoryException e) {
      createRepository();
    } catch (NotSupportedException e) {
      stateLog.error("Cannot replicate to " + uri, e, getStatesAsArray());
    } catch (TransportException e) {
      if (e instanceof UpdateRefFailureException) {
        updateRefRetryCount++;
        repLog.atSevere().log("Cannot replicate to %s due to a lock or write ref failure", uri);

        // The remote push operation should be retried.
        if (updateRefRetryCount <= maxUpdateRefRetries) {
          if (canceledWhileRunning.get()) {
            logCanceledWhileRunningException(e);
          } else {
            pool.reschedule(this, Destination.RetryReason.TRANSPORT_ERROR);
          }
        } else {
          retryDone();
          repLog.atSevere().log(
              "Giving up after %d '%s' failures during replication to %s",
              updateRefRetryCount, e.getMessage(), uri);
        }
      } else {
        if (canceledWhileRunning.get()) {
          logCanceledWhileRunningException(e);
        } else {
          repLog.atSevere().withCause(e).log("Cannot replicate to %s", uri);
          // The remote push operation should be retried.
          pool.reschedule(this, Destination.RetryReason.TRANSPORT_ERROR);
        }
      }
    } catch (IOException e) {
      stateLog.error("Cannot replicate to " + uri, e, getStatesAsArray());
    } catch (PermissionBackendException | RuntimeException | Error e) {
      stateLog.error("Unexpected error during replication to " + uri, e, getStatesAsArray());
    } finally {
      pool.notifyFinished(this);
      if (git != null) {
        git.close();
      }
    }
  }

  private void logCanceledWhileRunningException(TransportException e) {
    repLog.atInfo().withCause(e).log("Cannot replicate to %s. It was canceled while running", uri);
  }

  private void createRepository() {
    if (pool.isCreateMissingRepos()) {
      try {
        Ref head = git.exactRef(Constants.HEAD);
        if (createProject(projectName, head != null ? getName(head) : null)) {
          repLog.atWarning().log("Missing repository created; retry replication to %s", uri);
          pool.reschedule(this, Destination.RetryReason.REPOSITORY_MISSING);
        } else {
          repLog.atWarning().log(
              "Missing repository could not be created when replicating %s", uri);
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
    try (Transport tn = transportFactory.open(git, uri)) {
      res = pushVia(tn);
    }
    updateStates(res.getRemoteUpdates());
  }

  private PushResult pushVia(Transport tn) throws IOException, PermissionBackendException {
    tn.applyConfig(config);
    tn.setCredentialsProvider(credentialsFactory.create(config.getName()));

    List<RemoteRefUpdate> todo = generateUpdates(tn);
    if (todo.isEmpty()) {
      // If we have no commands selected, we have nothing to do.
      // Calling JGit at this point would just redo the work we
      // already did, and come up with the same answer. Instead
      // send back an empty result.
      return new PushResult();
    }

    if (replConfig.getMaxRefsToLog() == 0 || todo.size() <= replConfig.getMaxRefsToLog()) {
      repLog.atInfo().log("Push to %s references: %s", uri, lazy(() -> refUpdatesForLogging(todo)));
    } else {
      repLog.atInfo().log(
          "Push to %s references (first %d of %d listed): %s",
          uri,
          replConfig.getMaxRefsToLog(),
          todo.size(),
          lazy(() -> refUpdatesForLogging(todo.subList(0, replConfig.getMaxRefsToLog()))));
    }

    return pushInBatches(tn, todo);
  }

  private PushResult pushInBatches(Transport tn, List<RemoteRefUpdate> todo)
      throws NotSupportedException, TransportException {
    int batchSize = pool.getPushBatchSize();
    if (batchSize == 0 || todo.size() <= batchSize) {
      return tn.push(NullProgressMonitor.INSTANCE, todo);
    }

    List<List<RemoteRefUpdate>> batches = Lists.partition(todo, batchSize);
    repLog.atInfo().log("Push to %s in %d batches", uri, batches.size());
    AggregatedPushResult result = new AggregatedPushResult();
    int completedBatch = 1;
    for (List<RemoteRefUpdate> batch : batches) {
      repLog.atInfo().log(
          "Pushing %d/%d batches for replication to %s", completedBatch, batches.size(), uri);
      result.addResult(tn.push(NullProgressMonitor.INSTANCE, batch));

      //  check if push should be no longer continued
      if (wasCanceled()) {
        repLog.atInfo().log(
            "Push for replication to %s was canceled after %d completed batch and thus won't be"
                + " rescheduled",
            uri, completedBatch);
        break;
      }
      completedBatch++;
    }
    return result;
  }

  private static String refUpdatesForLogging(List<RemoteRefUpdate> refUpdates) {
    return refUpdates.stream().map(PushOne::refUpdateForLogging).collect(joining(", "));
  }

  private static String refUpdateForLogging(RemoteRefUpdate update) {
    String refSpec = String.format("%s:%s", update.getSrcRef(), update.getRemoteName());
    String id =
        String.format(
            "%s..%s", objectIdToString(update.getExpectedOldObjectId()), update.getNewObjectId());
    return MoreObjects.toStringHelper(RemoteRefUpdate.class)
        .add("refSpec", refSpec)
        .add("status", update.getStatus())
        .add("id", id)
        .add("force", booleanToString(update.isForceUpdate()))
        .add("delete", booleanToString(update.isDelete()))
        .add("ffwd", booleanToString(update.isFastForward()))
        .toString();
  }

  private static String objectIdToString(ObjectId id) {
    return id != null ? id.getName() : "(null)";
  }

  private static String booleanToString(boolean b) {
    return b ? "yes" : "no";
  }

  private List<RemoteRefUpdate> generateUpdates(Transport tn)
      throws IOException, PermissionBackendException {
    Optional<ProjectState> projectState = projectCache.get(projectName);
    if (!projectState.isPresent()) {
      return Collections.emptyList();
    }

    Map<String, Ref> local =
        git.getRefDatabase().getRefs().stream().collect(toMap(Ref::getName, r -> r));
    boolean filter;
    PermissionBackend.ForProject forProject = permissionBackend.currentUser().project(projectName);
    try {
      projectState.get().checkStatePermitsRead();
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
        for (String src : flattenRefBatchesToPush()) {
          Ref r = local.get(src);
          if (r != null) {
            n.put(src, r);
          }
        }
        local = n;
      }
      local =
          forProject
              .filter(local.values(), git, RefFilterOptions.builder().setFilterMeta(true).build())
              .stream()
              .collect(toMap(Ref::getName, r -> r));
    }

    List<RemoteRefUpdate> remoteUpdatesList =
        pushAllRefs ? doPushAll(tn, local) : doPushDelta(local);

    return replicationPushFilter == null || replicationPushFilter.get() == null
        ? remoteUpdatesList
        : replicationPushFilter.get().filter(projectName.get(), remoteUpdatesList);
  }

  private List<RemoteRefUpdate> doPushAll(Transport tn, Map<String, Ref> local) throws IOException {
    List<RemoteRefUpdate> cmds = new ArrayList<>();
    boolean noPerms = !pool.isReplicatePermissions();
    Map<String, Ref> remote = listRemote(tn);
    for (Ref src : local.values()) {
      if (!canPushRef(src.getName(), noPerms)) {
        repLog.atFine().log("Skipping push of ref %s", src.getName());
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
        if (Constants.HEAD.equals(ref.getName())) {
          repLog.atFine().log("Skipping deletion of %s", ref.getName());
          continue;
        }
        RefSpec spec = matchDst(ref.getName());
        if (spec != null && !local.containsKey(spec.getSource())) {
          // No longer on local side, request removal.
          delete(cmds, spec);
        }
      }
    }
    return cmds;
  }

  private List<RemoteRefUpdate> doPushDelta(Map<String, Ref> local) throws IOException {
    List<RemoteRefUpdate> cmds = new ArrayList<>();
    boolean noPerms = !pool.isReplicatePermissions();
    Set<String> refs = flattenRefBatchesToPush();
    for (String src : refs) {
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
        && !ref.startsWith(RefNames.REFS_CACHE_AUTOMERGE)
        && !(!pool.replicateNoteDbMetaRefs() && RefNames.isNoteDbMetaRef(ref));
  }

  private Map<String, Ref> listRemote(Transport tn)
      throws NotSupportedException, TransportException {
    try (FetchConnection fc = tn.openFetch()) {
      return fc.getRefsMap();
    }
  }

  @Nullable
  private RefSpec matchSrc(String ref) {
    for (RefSpec s : config.getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return s.expandFromSource(ref);
      }
    }
    return null;
  }

  @Nullable
  private RefSpec matchDst(String ref) {
    for (RefSpec s : config.getPushRefSpecs()) {
      if (s.matchDestination(ref)) {
        return s.expandFromDestination(ref);
      }
    }
    return null;
  }

  @VisibleForTesting
  void push(List<RemoteRefUpdate> cmds, RefSpec spec, Ref src) throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, src, dst, force, null, null));
  }

  private void delete(List<RemoteRefUpdate> cmds, RefSpec spec) throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, (Ref) null, dst, force, null, null));
  }

  private void updateStates(Collection<RemoteRefUpdate> refUpdates)
      throws UpdateRefFailureException {
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
          } else if (LOCK_FAILURE.equals(u.getMessage())
              || UPDATE_REF_FAILURE.equals(u.getMessage())) {
            throw new UpdateRefFailureException(uri, u.getMessage());
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

  private Set<String> flattenRefBatchesToPush() {
    return refBatchesToPush.stream().flatMap(Collection::stream).collect(Collectors.toSet());
  }

  public static class UpdateRefFailureException extends TransportException {
    private static final long serialVersionUID = 1L;

    UpdateRefFailureException(URIish uri, String message) {
      super(uri, message);
    }
  }

  /**
   * Internal class used to aggregate PushResult objects from all push batches. See {@link
   * PushOne#pushInBatches} for usage.
   */
  private static class AggregatedPushResult extends PushResult {
    private final List<PushResult> results = new ArrayList<>();

    void addResult(PushResult result) {
      results.add(result);
    }

    @Override
    public Collection<RemoteRefUpdate> getRemoteUpdates() {
      return results.stream()
          .map(PushResult::getRemoteUpdates)
          .flatMap(Collection::stream)
          .collect(toList());
    }
  }
}
