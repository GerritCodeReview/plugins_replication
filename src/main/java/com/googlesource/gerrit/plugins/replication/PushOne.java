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

import com.google.common.base.Throwables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.ChangeCache;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.ProjectRunnable;
import com.google.gerrit.server.git.TagCache;
import com.google.gerrit.server.git.VisibleRefFilter;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import com.jcraft.jsch.JSchException;

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
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A push to remote operation started by {@link GitReferenceUpdatedListener}.
 * <p>
 * Instance members are protected by the lock within PushQueue. Callers must
 * take that lock to ensure they are working with a current view of the object.
 */
class PushOne implements ProjectRunnable {
  private static final Logger log = ReplicationQueue.log;
  private static final WrappedLogger wrappedLog = new WrappedLogger(log);
  static final String ALL_REFS = "..all..";

  interface Factory {
    PushOne create(Project.NameKey d, URIish u);
  }

  private final GitRepositoryManager gitManager;
  private final SchemaFactory<ReviewDb> schema;
  private final Destination pool;
  private final RemoteConfig config;
  private final CredentialsProvider credentialsProvider;
  private final TagCache tagCache;
  private final PerThreadRequestScope.Scoper threadScoper;
  private final ChangeCache changeCache;

  private final Project.NameKey projectName;
  private final URIish uri;
  private final Set<String> delta = Sets.newHashSetWithExpectedSize(4);
  private boolean pushAllRefs;
  private Repository git;
  private boolean retrying;
  private boolean canceled;
  private final Multimap<String,ReplicationState> stateMap =
      LinkedListMultimap.create();

  @Inject
  PushOne(final GitRepositoryManager grm,
      final SchemaFactory<ReviewDb> s,
      final Destination p,
      final RemoteConfig c,
      final SecureCredentialsFactory cpFactory,
      final TagCache tc,
      final PerThreadRequestScope.Scoper ts,
      final ChangeCache cc,
      @Assisted final Project.NameKey d,
      @Assisted final URIish u) {
    gitManager = grm;
    schema = s;
    pool = p;
    config = c;
    credentialsProvider = cpFactory.create(c.getName());
    tagCache = tc;
    threadScoper = ts;
    changeCache = cc;
    projectName = d;
    uri = u;
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
    return "push " + uri;
  }

  boolean isRetrying() {
    return retrying;
  }

  void setToRetry() {
    retrying = true;
  }

  void cancel() {
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
    } else if (!pushAllRefs) {
      delta.add(ref);
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

  Multimap<String,ReplicationState> getStates() {
    return stateMap;
  }

  ReplicationState[] getStatesAsArray() {
    Set<ReplicationState> statesSet = new HashSet<ReplicationState>();
    statesSet.addAll(stateMap.values());
    return statesSet.toArray(new ReplicationState[statesSet.size()]);
  }

  ReplicationState[] getStatesByRef(String ref) {
    Collection<ReplicationState> states = stateMap.get(ref);
    return states.toArray(new ReplicationState[states.size()]);
  }

  void addStates(Multimap<String,ReplicationState> states) {
    stateMap.putAll(states);
  }

  void removeStates() {
    stateMap.clear();
  }

  private void statesCleanUp() {
    if (!stateMap.isEmpty() && !isRetrying()) {
      for (Map.Entry<String,ReplicationState> entry : stateMap.entries()) {
        entry.getValue().notifyRefReplicated(projectName.get(), entry.getKey(), uri,
            RefPushResult.FAILED);
      }
    }
  }

  @Override
  public void run() {
    try {
      threadScoper.scope(new Callable<Void>(){
        @Override
        public Void call() {
          runPushOperation();
          return null;
        }
      }).call();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    } finally {
      statesCleanUp();
    }
  }

  private void runPushOperation() {
    // Lock the queue, and remove ourselves, so we can't be modified once
    // we start replication (instead a new instance, with the same URI, is
    // created and scheduled for a future point in time.)
    //
    pool.notifyStarting(this);

    // It should only verify if it was canceled after calling notifyStarting,
    // since the canceled flag would be set locking the queue.
    if (!canceled) {
      try {
        git = gitManager.openRepository(projectName);
        runImpl();
      } catch (RepositoryNotFoundException e) {
        wrappedLog.error("Cannot replicate " + projectName + "; " + e.getMessage(), getStatesAsArray());

      } catch (RemoteRepositoryException e) {
        log.error("Cannot replicate " + projectName + "; " + e.getMessage());

      } catch (NoRemoteRepositoryException e) {
        wrappedLog.error("Cannot replicate to " + uri + "; repository not found", getStatesAsArray());

      } catch (NotSupportedException e) {
        wrappedLog.error("Cannot replicate to " + uri, e, getStatesAsArray());

      } catch (TransportException e) {
        Throwable cause = e.getCause();
        if (cause instanceof JSchException
            && cause.getMessage().startsWith("UnknownHostKey:")) {
          log.error("Cannot replicate to " + uri + ": " + cause.getMessage());
        } else {
          log.error("Cannot replicate to " + uri, e);
        }

        // The remote push operation should be retried.
        pool.reschedule(this);
      } catch (IOException e) {
        wrappedLog.error("Cannot replicate to " + uri, e, getStatesAsArray());

      } catch (RuntimeException e) {
        wrappedLog.error("Unexpected error during replication to " + uri, e, getStatesAsArray());

      } catch (Error e) {
        wrappedLog.error("Unexpected error during replication to " + uri, e, getStatesAsArray());

      } finally {
        if (git != null) {
          git.close();
        }
      }
    }
  }

  private void runImpl() throws IOException {
    Transport tn = Transport.open(git, uri);
    PushResult res;
    try {
      res = pushVia(tn);
    } finally {
      try {
        tn.close();
      } catch (Throwable e2) {
        log.warn("Unexpected error while closing " + uri, e2);
      }
    }

    updateStates(res.getRemoteUpdates());
  }

  private PushResult pushVia(Transport tn)
      throws IOException, NotSupportedException, TransportException {
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

    return tn.push(NullProgressMonitor.INSTANCE, todo);
  }

  private List<RemoteRefUpdate> generateUpdates(Transport tn)
      throws IOException {
    ProjectControl pc;
    try {
      pc = pool.controlFor(projectName);
    } catch (NoSuchProjectException e) {
      return Collections.emptyList();
    }

    Map<String, Ref> local = git.getAllRefs();
    if (!pc.allRefsAreVisible()) {
      if (!pushAllRefs) {
        // If we aren't mirroring, reduce the space we need to filter
        // to only the references we will update during this operation.
        //
        Map<String, Ref> n = Maps.newHashMap();
        for (String src : delta) {
          Ref r = local.get(src);
          if (r != null) {
            n.put(src, r);
          }
        }
        local = n;
      }

      ReviewDb db;
      try {
        db = schema.open();
      } catch (OrmException e) {
        wrappedLog.error("Cannot read database to replicate to " + projectName, e, getStatesAsArray());
        return Collections.emptyList();
      }
      try {
        local = new VisibleRefFilter(tagCache, changeCache, git, pc, db, true)
            .filter(local, true);
      } finally {
        db.close();
      }
    }

    return pushAllRefs ? doPushAll(tn, local) : doPushDelta(local);
  }

  private List<RemoteRefUpdate> doPushAll(Transport tn, Map<String, Ref> local)
      throws NotSupportedException, TransportException, IOException {
    List<RemoteRefUpdate> cmds = Lists.newArrayList();
    boolean noPerms = !pool.isReplicatePermissions();
    Map<String, Ref> remote = listRemote(tn);
    for (Ref src : local.values()) {
      if (noPerms && GitRepositoryManager.REF_CONFIG.equals(src.getName())) {
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

  private List<RemoteRefUpdate> doPushDelta(Map<String, Ref> local)
      throws IOException {
    List<RemoteRefUpdate> cmds = Lists.newArrayList();
    boolean noPerms = !pool.isReplicatePermissions();
    for (String src : delta) {
      RefSpec spec = matchSrc(src);
      if (spec != null) {
        // If the ref still exists locally, send it, otherwise delete it.
        Ref srcRef = local.get(src);
        if (srcRef != null &&
            !(noPerms && GitRepositoryManager.REF_CONFIG.equals(src))) {
          push(cmds, spec, srcRef);
        } else if (config.isMirror()) {
          delete(cmds, spec);
        }
      }
    }
    return cmds;
  }

  private Map<String, Ref> listRemote(Transport tn)
      throws NotSupportedException, TransportException {
    FetchConnection fc = tn.openFetch();
    try {
      return fc.getRefsMap();
    } finally {
      fc.close();
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

  private void push(List<RemoteRefUpdate> cmds, RefSpec spec, Ref src)
      throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, src, dst, force, null, null));
  }

  private void delete(List<RemoteRefUpdate> cmds, RefSpec spec)
      throws IOException {
    String dst = spec.getDestination();
    boolean force = spec.isForceUpdate();
    cmds.add(new RemoteRefUpdate(git, (Ref) null, dst, force, null, null));
  }

  private void updateStates(Collection<RemoteRefUpdate> refUpdates) {
    Set<String> doneRefs = new HashSet<String>();
    boolean anyRefFailed = false;

    for (RemoteRefUpdate u : refUpdates) {
      RefPushResult pushStatus = RefPushResult.SUCCEEDED;
      Set<ReplicationState> logStates = new HashSet<ReplicationState>();

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
          wrappedLog.error(String.format("Failed replicate of %s to %s: status %s",
              u.getRemoteName(), uri, u.getStatus()), logStatesArray);
          pushStatus = RefPushResult.FAILED;
          anyRefFailed = true;
          break;

        case REJECTED_OTHER_REASON:
          if ("non-fast-forward".equals(u.getMessage())) {
            wrappedLog.error(String.format("Failed replicate of %s to %s"
                + ", remote rejected non-fast-forward push."
                + "  Check receive.denyNonFastForwards variable in config file"
                + " of destination repository.", u.getRemoteName(), uri), logStatesArray);
          } else {
            wrappedLog.error(String.format(
                "Failed replicate of %s to %s, reason: %s",
                u.getRemoteName(), uri, u.getMessage()), logStatesArray);
          }
          pushStatus = RefPushResult.FAILED;
          anyRefFailed = true;
          break;
      }

      for (ReplicationState rs : getStatesByRef(u.getSrcRef())) {
        rs.notifyRefReplicated(projectName.get(), u.getSrcRef(),
              uri, pushStatus);
      }
    }

    doneRefs.add(ALL_REFS);
    for (ReplicationState rs : getStatesByRef(ALL_REFS)) {
      rs.notifyRefReplicated(projectName.get(), ALL_REFS,
          uri, anyRefFailed ? RefPushResult.FAILED : RefPushResult.SUCCEEDED);
    }
    for (Map.Entry<String,ReplicationState> entry : stateMap.entries()) {
      if (!doneRefs.contains(entry.getKey())) {
        entry.getValue().notifyRefReplicated(projectName.get(), entry.getKey(), uri,
            RefPushResult.NOT_ATTEMPTED);
      }
    }
    stateMap.clear();
  }
}
