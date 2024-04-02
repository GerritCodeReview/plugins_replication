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

import static com.google.gerrit.server.project.ProjectCache.noSuchProject;
import static com.googlesource.gerrit.plugins.replication.ReplicationConfigImpl.replaceName;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.NON_EXISTING;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.util.logging.NamedFluentLogger;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.servlet.RequestScoped;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.ReplicationScheduledEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

public class Destination {
  private static final NamedFluentLogger repLog = ReplicationQueue.repLog;

  private static final String PROJECT_NOT_AVAILABLE = "source project %s not available";

  public interface Factory {
    Destination create(DestinationConfiguration config);
  }

  private final ReplicationStateListener stateLog;
  private final Object stateLock = new Object();
  // writes are covered by the stateLock, but some reads are still
  // allowed without the lock
  private final ConcurrentMap<URIish, PushOne> pending = new ConcurrentHashMap<>();
  private final Map<URIish, PushOne> inFlight = new HashMap<>();
  private final PushOne.Factory opFactory;
  private final DeleteProjectTask.Factory deleteProjectFactory;
  private final UpdateHeadTask.Factory updateHeadFactory;
  private final GitRepositoryManager gitManager;
  private final PermissionBackend permissionBackend;
  private final Provider<CurrentUser> userProvider;
  private final ProjectCache projectCache;
  private volatile ScheduledExecutorService pool;
  private final PerThreadRequestScope.Scoper threadScoper;
  private final DestinationConfiguration config;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Provider<ReplicationTasksStorage> replicationTasksStorage;

  protected enum RetryReason {
    TRANSPORT_ERROR,
    COLLISION,
    REPOSITORY_MISSING;
  }

  public static class QueueInfo {
    public final ImmutableMap<URIish, PushOne> pending;
    public final ImmutableMap<URIish, PushOne> inFlight;

    public QueueInfo(Map<URIish, PushOne> pending, Map<URIish, PushOne> inFlight) {
      this.pending = ImmutableMap.copyOf(pending);
      this.inFlight = ImmutableMap.copyOf(inFlight);
    }
  }

  @Inject
  protected Destination(
      Injector injector,
      PluginUser pluginUser,
      GitRepositoryManager gitRepositoryManager,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> userProvider,
      ProjectCache projectCache,
      GroupBackend groupBackend,
      ReplicationStateListeners stateLog,
      GroupIncludeCache groupIncludeCache,
      DynamicItem<EventDispatcher> eventDispatcher,
      Provider<ReplicationTasksStorage> rts,
      @Assisted DestinationConfiguration cfg) {
    this.eventDispatcher = eventDispatcher;
    gitManager = gitRepositoryManager;
    this.permissionBackend = permissionBackend;
    this.userProvider = userProvider;
    this.projectCache = projectCache;
    this.stateLog = stateLog;
    this.replicationTasksStorage = rts;
    config = cfg;
    CurrentUser remoteUser;
    if (!cfg.getAuthGroupNames().isEmpty()) {
      ImmutableSet.Builder<AccountGroup.UUID> builder = ImmutableSet.builder();
      for (String name : cfg.getAuthGroupNames()) {
        GroupReference g = GroupBackends.findExactSuggestion(groupBackend, name);
        if (g != null) {
          builder.add(g.getUUID());
          addRecursiveParents(g.getUUID(), builder, groupIncludeCache);
        } else {
          repLog.atWarning().log("Group \"%s\" not recognized, removing from authGroup", name);
        }
      }
      remoteUser = new RemoteSiteUser(new ListGroupMembership(builder.build()));
    } else {
      remoteUser = pluginUser;
    }

    Injector child =
        injector.createChildInjector(
            new FactoryModule() {
              @Override
              protected void configure() {
                bindScope(RequestScoped.class, PerThreadRequestScope.REQUEST);
                bind(PerThreadRequestScope.Propagator.class);

                bind(Destination.class).toInstance(Destination.this);
                bind(RemoteConfig.class).toInstance(config.getRemoteConfig());
                install(new FactoryModuleBuilder().build(PushOne.Factory.class));
                install(new FactoryModuleBuilder().build(CreateProjectTask.Factory.class));
                install(new FactoryModuleBuilder().build(DeleteProjectTask.Factory.class));
                install(new FactoryModuleBuilder().build(UpdateHeadTask.Factory.class));

                DynamicItem.itemOf(binder(), AdminApiFactory.class);
                DynamicItem.bind(binder(), AdminApiFactory.class)
                    .to(AdminApiFactory.DefaultAdminApiFactory.class);

                install(new FactoryModuleBuilder().build(GerritRestApi.Factory.class));
              }

              @Provides
              public PerThreadRequestScope.Scoper provideScoper(
                  final PerThreadRequestScope.Propagator propagator) {
                final RequestContext requestContext = () -> remoteUser;
                return new PerThreadRequestScope.Scoper() {
                  @Override
                  public <T> Callable<T> scope(Callable<T> callable) {
                    return propagator.scope(requestContext, callable);
                  }
                };
              }
            });

    opFactory = child.getInstance(PushOne.Factory.class);
    deleteProjectFactory = child.getInstance(DeleteProjectTask.Factory.class);
    updateHeadFactory = child.getInstance(UpdateHeadTask.Factory.class);
    threadScoper = child.getInstance(PerThreadRequestScope.Scoper.class);
  }

  private void addRecursiveParents(
      AccountGroup.UUID g,
      ImmutableSet.Builder<AccountGroup.UUID> builder,
      GroupIncludeCache groupIncludeCache) {
    for (AccountGroup.UUID p : groupIncludeCache.parentGroupsOf(g)) {
      if (builder.build().contains(p)) {
        continue;
      }
      builder.add(p);
      addRecursiveParents(p, builder, groupIncludeCache);
    }
  }

  public QueueInfo getQueueInfo() {
    synchronized (stateLock) {
      return new QueueInfo(pending, inFlight);
    }
  }

  public void start(WorkQueue workQueue) {
    String poolName = "ReplicateTo-" + config.getRemoteConfig().getName();
    pool = workQueue.createQueue(config.getPoolThreads(), poolName);
  }

  public int shutdown() {
    int cnt = 0;
    if (pool != null) {
      synchronized (stateLock) {
        int numPending = pending.size();
        int numInFlight = inFlight.size();

        if (numPending > 0 || numInFlight > 0) {
          repLog.atWarning().log(
              "Cancelling replication events (pending=%d, inFlight=%d) for destination %s",
              numPending, numInFlight, getRemoteConfigName());

          foreachPushOp(
              pending,
              push -> {
                push.cancel();
                return null;
              });
          pending.clear();
          foreachPushOp(
              inFlight,
              push -> {
                push.setCanceledWhileRunning();
                return null;
              });
          inFlight.clear();
        }
        cnt = pool.shutdownNow().size();
        pool = null;
      }
    }
    return cnt;
  }

  private void foreachPushOp(Map<URIish, PushOne> opsMap, Function<PushOne, Void> pushOneFunction) {
    // Callers may modify the provided opsMap concurrently, hence make a defensive copy of the
    // values to loop over them.
    for (PushOne pushOne : ImmutableList.copyOf(opsMap.values())) {
      pushOneFunction.apply(pushOne);
    }
  }

  private boolean shouldReplicate(ProjectState state, CurrentUser user)
      throws PermissionBackendException {
    String name = state.getProject().getName();
    if (!config.replicateHiddenProjects()
        && state.getProject().getState()
            == com.google.gerrit.extensions.client.ProjectState.HIDDEN) {
      repLog.atFine().log(
          "Project %s is hidden and replication of hidden projects is disabled", name);
      return false;
    }

    // Hidden projects(permitsRead = false) should only be accessible by the project owners.
    // READ_CONFIG is checked here because it's only allowed to project owners(ACCESS may also
    // be allowed for other users).
    ProjectPermission permissionToCheck =
        state.statePermitsRead() ? ProjectPermission.ACCESS : ProjectPermission.READ_CONFIG;
    try {
      permissionBackend.user(user).project(state.getNameKey()).check(permissionToCheck);
      return true;
    } catch (AuthException e) {
      repLog.atFine().log(
          "Project %s is not visible to current user %s",
          name, user.getUserName().orElse("unknown"));
      return false;
    }
  }

  private boolean shouldReplicate(
      final Project.NameKey project, String ref, ReplicationState... states) {
    try {
      return threadScoper
          .scope(
              () -> {
                ProjectState projectState;
                try {
                  projectState = projectCache.get(project).orElseThrow(noSuchProject(project));
                } catch (StorageException e) {
                  repLog.atWarning().withCause(e).log(
                      "Error reading project %s from cache", project);
                  return false;
                }
                if (projectState == null) {
                  repLog.atFine().log("Project %s does not exist", project);
                  throw new NoSuchProjectException(project);
                }
                if (!projectState.statePermitsRead()) {
                  repLog.atFine().log("Project %s does not permit read", project);
                  return false;
                }
                if (!shouldReplicate(projectState, userProvider.get())) {
                  repLog.atFine().log("Project %s should not be replicated", project);
                  return false;
                }
                if (PushOne.ALL_REFS.equals(ref)) {
                  return true;
                }
                if (userProvider.get().isInternalUser()) {
                  return true;
                }
                try {
                  permissionBackend
                      .user(userProvider.get())
                      .project(project)
                      .ref(ref)
                      .check(RefPermission.READ);
                } catch (AuthException e) {
                  repLog.atFine().log(
                      "Ref %s on project %s is not visible to calling user %s",
                      ref, project, userProvider.get().getUserName().orElse("unknown"));
                  return false;
                }
                return true;
              })
          .call();
    } catch (NoSuchProjectException err) {
      stateLog.error(String.format(PROJECT_NOT_AVAILABLE, project), err, states);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
    return false;
  }

  private boolean shouldReplicate(Project.NameKey project, ReplicationState... states) {
    try {
      return threadScoper
          .scope(
              () -> {
                ProjectState projectState;
                try {
                  projectState = projectCache.get(project).orElseThrow(noSuchProject(project));
                } catch (StorageException e) {
                  return false;
                }
                return shouldReplicate(projectState, userProvider.get());
              })
          .call();
    } catch (NoSuchProjectException err) {
      stateLog.error(String.format(PROJECT_NOT_AVAILABLE, project), err, states);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
    return false;
  }

  void schedule(Project.NameKey project, Set<String> refs, URIish uri, ReplicationState state) {
    schedule(project, refs, uri, state, false);
  }

  void schedule(
      Project.NameKey project, Set<String> refs, URIish uri, ReplicationState state, boolean now) {
    ImmutableSet.Builder<String> toSchedule = ImmutableSet.builder();
    for (String ref : refs) {
      if (!shouldReplicate(project, ref, state)) {
        repLog.atFine().log("Not scheduling replication %s:%s => %s", project, ref, uri);
        continue;
      }
      toSchedule.add(ref);
    }
    repLog.atInfo().log("scheduling replication %s:%s => %s", project, refs, uri);

    if (!config.replicatePermissions()) {
      PushOne e;
      synchronized (stateLock) {
        e = getPendingPush(uri);
      }
      if (e == null) {
        try (Repository git = gitManager.openRepository(project)) {
          try {
            Ref head = git.exactRef(Constants.HEAD);
            if (head != null
                && head.isSymbolic()
                && RefNames.REFS_CONFIG.equals(head.getLeaf().getName())) {
              return;
            }
          } catch (IOException err) {
            stateLog.error(String.format("cannot check type of project %s", project), err, state);
            return;
          }
        } catch (IOException err) {
          stateLog.error(String.format(PROJECT_NOT_AVAILABLE, project), err, state);
          return;
        }
      }
    }

    ImmutableSet<String> refsToSchedule = toSchedule.build();
    PushOne task;
    synchronized (stateLock) {
      task = getPendingPush(uri);
      if (task == null) {
        task = opFactory.create(project, uri);
        task.addRefBatch(refsToSchedule);
        task.addState(refsToSchedule, state);
        @SuppressWarnings("unused")
        ScheduledFuture<?> ignored =
            pool.schedule(task, now ? 0 : config.getDelay(), TimeUnit.SECONDS);
        pending.put(uri, task);
        repLog.atInfo().log(
            "scheduled %s:%s => %s to run %s",
            project, refsToSchedule, task, now ? "now" : "after " + config.getDelay() + "s");
      } else {
        task.addRefBatch(refsToSchedule);
        task.addState(refsToSchedule, state);
        repLog.atInfo().log(
            "consolidated %s:%s => %s with an existing pending push",
            project, refsToSchedule, task);
      }
      for (String ref : refsToSchedule) {
        state.increasePushTaskCount(project.get(), ref);
      }
    }
    postReplicationScheduledEvent(task, refsToSchedule);
  }

  @Nullable
  private PushOne getPendingPush(URIish uri) {
    PushOne e = pending.get(uri);
    if (e != null && !e.wasCanceled()) {
      return e;
    }
    return null;
  }

  void pushWasCanceled(PushOne pushOp) {
    Set<ImmutableSet<String>> notAttemptedRefs = Collections.emptySet();
    synchronized (stateLock) {
      URIish uri = pushOp.getURI();
      pending.remove(uri);
      notAttemptedRefs = pushOp.getRefs();
    }
    pushOp.notifyNotAttempted(notAttemptedRefs);
  }

  void scheduleDeleteProject(URIish uri, Project.NameKey project, ProjectDeletionState state) {
    repLog.atFine().log("scheduling deletion of project %s at %s", project, uri);
    @SuppressWarnings("unused")
    ScheduledFuture<?> ignored =
        pool.schedule(deleteProjectFactory.create(uri, project, state), 0, TimeUnit.SECONDS);
    state.setScheduled(uri);
  }

  void scheduleUpdateHead(URIish uri, Project.NameKey project, String newHead) {
    @SuppressWarnings("unused")
    ScheduledFuture<?> ignored =
        pool.schedule(updateHeadFactory.create(uri, project, newHead), 0, TimeUnit.SECONDS);
  }

  /**
   * It schedules again a PushOp instance.
   *
   * <p>If the reason for rescheduling is to avoid a collision with an in-flight push to the same
   * URI, we don't mark the operation as "retrying," and we schedule using the replication delay,
   * rather than the retry delay. Otherwise, the operation is marked as "retrying" and scheduled to
   * run following the minutes count determined by class attribute retryDelay.
   *
   * <p>In case the PushOp instance to be scheduled has same URI than one marked as "retrying," it
   * adds to the one pending the refs list of the parameter instance.
   *
   * <p>In case the PushOp instance to be scheduled has the same URI as one pending, but not marked
   * "retrying," it indicates the one pending should be canceled when it starts executing, removes
   * it from pending list, and adds its refs to the parameter instance. The parameter instance is
   * scheduled for retry.
   *
   * <p>Notice all operations to indicate a PushOp should be canceled, or it is retrying, or
   * remove/add it from/to pending Map should be protected by synchronizing on the stateLock object.
   *
   * @param pushOp The PushOp instance to be scheduled.
   */
  void reschedule(PushOne pushOp, RetryReason reason) {
    boolean isRescheduled = false;
    boolean isFailed = false;
    RemoteRefUpdate.Status failedStatus = null;

    synchronized (stateLock) {
      URIish uri = pushOp.getURI();
      PushOne pendingPushOp = getPendingPush(uri);

      if (pendingPushOp != null) {
        // There is one PushOp instance already pending to same URI.

        if (pendingPushOp.isRetrying()) {
          // The one pending is one already retrying, so it should
          // maintain it and add to it the refs of the one passed
          // as parameter to the method.

          // This scenario would happen if a PushOp has started running
          // and then before it failed due transport exception, another
          // one to same URI started. The first one would fail and would
          // be rescheduled, being present in pending list. When the
          // second one fails, it will also be rescheduled and then,
          // here, find out replication to its URI is already pending
          // for retry (blocking).
          pendingPushOp.addRefBatches(pushOp.getRefs());
          pendingPushOp.addStates(pushOp.getStates());
          pushOp.removeStates();

        } else {
          // The one pending is one that is NOT retrying, it was just
          // scheduled believing no problem would happen. The one pending
          // should be canceled, and this is done by setting its canceled
          // flag, removing it from pending list, and adding its refs to
          // the pushOp instance that should then, later, in this method,
          // be scheduled for retry.

          // Notice that the PushOp found pending will start running and,
          // when notifying it is starting (with pending lock protection),
          // it will see it was canceled and then it will do nothing with
          // pending list and it will not execute its run implementation.
          pendingPushOp.canceledByReplication();
          pending.remove(uri);

          pushOp.addRefBatches(pendingPushOp.getRefs());
          pushOp.addStates(pendingPushOp.getStates());
          pendingPushOp.removeStates();
        }
      }

      if (pendingPushOp == null || !pendingPushOp.isRetrying()) {
        pending.put(uri, pushOp);
        switch (reason) {
          case COLLISION:
            @SuppressWarnings("unused")
            ScheduledFuture<?> ignored =
                pool.schedule(pushOp, config.getRescheduleDelay(), TimeUnit.SECONDS);
            break;
          case TRANSPORT_ERROR:
          case REPOSITORY_MISSING:
          default:
            failedStatus =
                RetryReason.REPOSITORY_MISSING.equals(reason)
                    ? NON_EXISTING
                    : REJECTED_OTHER_REASON;
            isFailed = true;
            if (pushOp.setToRetry()) {
              isRescheduled = true;
              replicationTasksStorage.get().reset(pushOp);
              @SuppressWarnings("unused")
              ScheduledFuture<?> ignored2 =
                  pool.schedule(pushOp, config.getRetryDelay(), TimeUnit.MINUTES);
            } else {
              pushOp.canceledByReplication();
              pushOp.retryDone();
              pending.remove(uri);
              stateLog.error(
                  "Push to " + pushOp.getURI() + " cancelled after maximum number of retries",
                  pushOp.getStatesAsArray());
            }
            break;
        }
      }
    }
    if (isFailed) {
      postReplicationFailedEvent(pushOp, failedStatus);
    }
    if (isRescheduled) {
      postReplicationScheduledEvent(pushOp);
    }
  }

  RunwayStatus requestRunway(PushOne op) {
    synchronized (stateLock) {
      if (op.wasCanceled()) {
        return RunwayStatus.canceled();
      }
      pending.remove(op.getURI());
      PushOne inFlightOp = inFlight.get(op.getURI());
      if (inFlightOp != null) {
        return RunwayStatus.denied(inFlightOp.getId());
      }
      op.notifyNotAttempted(op.setStartedRefs(replicationTasksStorage.get().start(op)));
      inFlight.put(op.getURI(), op);
    }
    return RunwayStatus.allowed();
  }

  void notifyFinished(PushOne op) {
    synchronized (stateLock) {
      if (!op.isRetrying()) {
        replicationTasksStorage.get().finish(op);
      }
      inFlight.remove(op.getURI());
    }
  }

  public Map<ReplicateRefUpdate, String> getTaskNamesByReplicateRefUpdate() {
    Map<ReplicateRefUpdate, String> taskNameByReplicateRefUpdate = new HashMap<>();
    for (PushOne push : pending.values()) {
      String taskName = push.toString();
      for (ReplicateRefUpdate refUpdate : push.getReplicateRefUpdates()) {
        taskNameByReplicateRefUpdate.put(refUpdate, taskName);
      }
    }
    return taskNameByReplicateRefUpdate;
  }

  boolean wouldPush(URIish uri, Project.NameKey project, String ref) {
    return matches(uri, project) && wouldPushProject(project) && wouldPushRef(ref);
  }

  boolean wouldPushProject(Project.NameKey project) {
    if (!shouldReplicate(project)) {
      repLog.atFine().log("Skipping replication of project %s", project.get());
      return false;
    }

    // by default push all projects
    ImmutableList<String> projects = config.getProjects();
    if (projects.isEmpty()) {
      return true;
    }

    boolean matches = new ReplicationFilter(projects).matches(project);
    if (!matches) {
      repLog.atFine().log(
          "Skipping replication of project %s; does not match filter", project.get());
    }
    return matches;
  }

  boolean isSingleProjectMatch() {
    return config.isSingleProjectMatch();
  }

  boolean wouldPushRef(String ref) {
    if (!config.replicatePermissions() && RefNames.REFS_CONFIG.equals(ref)) {
      repLog.atFine().log("Skipping push of ref %s; it is a meta ref", ref);
      return false;
    }
    if (PushOne.ALL_REFS.equals(ref)) {
      return true;
    }
    for (RefSpec s : config.getRemoteConfig().getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return true;
      }
    }
    repLog.atFine().log("Skipping push of ref %s; it does not match push ref specs", ref);
    return false;
  }

  boolean isCreateMissingRepos() {
    return config.createMissingRepos();
  }

  boolean isReplicatePermissions() {
    return config.replicatePermissions();
  }

  boolean isReplicateProjectDeletions() {
    return config.replicateProjectDeletions();
  }

  private boolean matches(URIish uri, Project.NameKey project) {
    for (URIish configUri : config.getRemoteConfig().getURIs()) {
      try {
        URIish projectUri = getURI(configUri, project);
        if (uri.equals(projectUri)) {
          return true;
        }
      } catch (URISyntaxException e) {
        repLog.atSevere().withCause(e).log(
            "remote config uri %s has invalid syntax with project %s", configUri, project);
      }
    }
    return false;
  }

  List<URIish> getURIs(Project.NameKey project, String urlMatch) {
    List<URIish> r = Lists.newArrayListWithCapacity(config.getRemoteConfig().getURIs().size());
    for (URIish configUri : config.getRemoteConfig().getURIs()) {
      try {
        URIish uri = getURI(configUri, project);
        if (matches(configUri, urlMatch) || matches(uri, urlMatch)) {
          r.add(uri);
        }
      } catch (URISyntaxException e) {
        repLog.atSevere().withCause(e).log(
            "remote config uri %s has invalid syntax with project %s", configUri, project);
      }
    }
    return r;
  }

  URIish getURI(URIish template, Project.NameKey project) throws URISyntaxException {
    return getURI(template, project, config.getRemoteNameStyle(), config.isSingleProjectMatch());
  }

  @VisibleForTesting
  static URIish getURI(
      URIish template,
      Project.NameKey project,
      String remoteNameStyle,
      boolean isSingleProjectMatch)
      throws URISyntaxException {
    String name = project.get();
    if (needsUrlEscaping(template)) {
      name = escape(name);
    }

    if (remoteNameStyle.equals("dash")) {
      name = name.replace("/", "-");
    } else if (remoteNameStyle.equals("underscore")) {
      name = name.replace("/", "_");
    } else if (remoteNameStyle.equals("basenameOnly")) {
      name = Files.getNameWithoutExtension(name);
    } else if (!remoteNameStyle.equals("slash")) {
      repLog.atFine().log("Unknown remoteNameStyle: %s, falling back to slash", remoteNameStyle);
    }
    String replacedPath = replaceName(template.getPath(), name, isSingleProjectMatch);
    return (replacedPath != null) ? template.setRawPath(replacedPath) : template;
  }

  static boolean needsUrlEscaping(URIish uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
        || "https".equalsIgnoreCase(uri.getScheme())
        || "amazon-s3".equalsIgnoreCase(uri.getScheme());
  }

  static String escape(String str) {
    // The '/' character is always encoded as %2F however, remote servers will expect it to be not
    // encoded as part of the path used to the repository. The fix to avoid this would be to build
    // up the path and escape each segment separately.
    return UrlEscapers.urlPathSegmentEscaper().escape(str).replaceAll("%2[fF]", "/");
  }

  ImmutableList<String> getAdminUrls() {
    return config.getAdminUrls();
  }

  ImmutableList<String> getUrls() {
    return config.getUrls();
  }

  ImmutableList<String> getAuthGroupNames() {
    return config.getAuthGroupNames();
  }

  ImmutableList<String> getProjects() {
    return config.getProjects();
  }

  int getUpdateRefErrorMaxRetries() {
    return config.getUpdateRefErrorMaxRetries();
  }

  String getRemoteConfigName() {
    return config.getRemoteConfig().getName();
  }

  public int getMaxRetries() {
    return config.getMaxRetries();
  }

  public int getDrainQueueAttempts() {
    return config.getDrainQueueAttempts();
  }

  public long getReplicationDelayMilliseconds() {
    return config.getDelay() * 1000L;
  }

  int getSlowLatencyThreshold() {
    return config.getSlowLatencyThreshold();
  }

  int getPushBatchSize() {
    return config.getPushBatchSize();
  }

  boolean replicateNoteDbMetaRefs() {
    return config.replicateNoteDbMetaRefs();
  }

  private static boolean matches(URIish uri, String urlMatch) {
    if (urlMatch == null || urlMatch.equals("") || urlMatch.equals("*")) {
      return true;
    }
    return uri.toString().contains(urlMatch);
  }

  private void postReplicationScheduledEvent(PushOne pushOp) {
    postReplicationScheduledEvent(pushOp, null);
  }

  private void postReplicationScheduledEvent(PushOne pushOp, ImmutableSet<String> inputRefs) {
    Set<ImmutableSet<String>> refBatches = inputRefs == null ? pushOp.getRefs() : Set.of(inputRefs);
    Project.NameKey project = pushOp.getProjectNameKey();
    for (String ref : flattenSetOfRefBatches(refBatches)) {
      ReplicationScheduledEvent event =
          new ReplicationScheduledEvent(project.get(), ref, pushOp.getURI());
      try {
        eventDispatcher.get().postEvent(BranchNameKey.create(project, ref), event);
      } catch (PermissionBackendException e) {
        repLog.atSevere().withCause(e).log("error posting event");
      }
    }
  }

  private void postReplicationFailedEvent(PushOne pushOp, RemoteRefUpdate.Status status) {
    Project.NameKey project = pushOp.getProjectNameKey();
    for (String ref : flattenSetOfRefBatches(pushOp.getRefs())) {
      RefReplicatedEvent event =
          new RefReplicatedEvent(project.get(), ref, pushOp.getURI(), RefPushResult.FAILED, status);
      try {
        eventDispatcher.get().postEvent(BranchNameKey.create(project, ref), event);
      } catch (PermissionBackendException e) {
        repLog.atSevere().withCause(e).log("error posting event");
      }
    }
  }

  private Set<String> flattenSetOfRefBatches(Set<ImmutableSet<String>> refBatches) {
    return refBatches.stream().flatMap(Collection::stream).collect(Collectors.toSet());
  }
}
