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

import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.resolveNodeName;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.NON_EXISTING;
import static org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.config.RequestScopedReviewDbProvider;
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
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.servlet.RequestScoped;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;

public class Destination {
  private static final Logger repLog = ReplicationQueue.repLog;

  private static final String PROJECT_NOT_AVAILABLE = "source project %s not available";

  public interface Factory {
    Destination create(DestinationConfiguration config);
  }

  private final ReplicationStateListener stateLog;
  private final Object stateLock = new Object();
  private final Map<URIish, PushOne> pending = new HashMap<>();
  private final Map<URIish, PushOne> inFlight = new HashMap<>();
  private final PushOne.Factory opFactory;
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
    public final Map<URIish, PushOne> pending;
    public final Map<URIish, PushOne> inFlight;

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
          repLog.warn("Group \"{}\" not recognized, removing from authGroup", name);
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
              }

              @Provides
              public PerThreadRequestScope.Scoper provideScoper(
                  final PerThreadRequestScope.Propagator propagator,
                  final Provider<RequestScopedReviewDbProvider> dbProvider) {
                final RequestContext requestContext =
                    new RequestContext() {
                      @Override
                      public CurrentUser getUser() {
                        return remoteUser;
                      }

                      @Override
                      public Provider<ReviewDb> getReviewDbProvider() {
                        return dbProvider.get();
                      }
                    };
                return new PerThreadRequestScope.Scoper() {
                  @Override
                  public <T> Callable<T> scope(Callable<T> callable) {
                    return propagator.scope(requestContext, callable);
                  }
                };
              }
            });

    opFactory = child.getInstance(PushOne.Factory.class);
    threadScoper = child.getInstance(PerThreadRequestScope.Scoper.class);
  }

  private void addRecursiveParents(
      AccountGroup.UUID g,
      Builder<AccountGroup.UUID> builder,
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
      repLog.warn("Cancelling replication events");

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
      cnt = pool.shutdownNow().size();
      pool = null;
    }
    return cnt;
  }

  private void foreachPushOp(Map<URIish, PushOne> opsMap, Function<PushOne, Void> pushOneFunction) {
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
      repLog.debug("Project {} is hidden and replication of hidden projects is disabled", name);
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
      repLog.debug(
          "Project {} is not visible to current user {}",
          name,
          user.getUserName().orElse("unknown"));
      return false;
    }
  }

  private boolean shouldReplicate(
      final Project.NameKey project, String ref, ReplicationState... states) {
    try {
      return threadScoper
          .scope(
              new Callable<Boolean>() {
                @Override
                public Boolean call() throws NoSuchProjectException, PermissionBackendException {
                  ProjectState projectState;
                  try {
                    projectState = projectCache.checkedGet(project);
                  } catch (IOException e) {
                    repLog.warn("Error reading project {} from cache", project, e);
                    return false;
                  }
                  if (projectState == null) {
                    repLog.debug("Project {} does not exist", project);
                    throw new NoSuchProjectException(project);
                  }
                  if (!projectState.statePermitsRead()) {
                    repLog.debug("Project {} does not permit read", project);
                    return false;
                  }
                  if (!shouldReplicate(projectState, userProvider.get())) {
                    repLog.debug("Project {} should not be replicated", project);
                    return false;
                  }
                  if (PushOne.ALL_REFS.equals(ref)) {
                    return true;
                  }
                  try {
                    permissionBackend
                        .user(userProvider.get())
                        .project(project)
                        .ref(ref)
                        .check(RefPermission.READ);
                  } catch (AuthException e) {
                    repLog.debug(
                        "Ref {} on project {} is not visible to calling user {}",
                        ref,
                        project,
                        userProvider.get().getUserName().orElse("unknown"));
                    return false;
                  }
                  return true;
                }
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
              new Callable<Boolean>() {
                @Override
                public Boolean call() throws NoSuchProjectException, PermissionBackendException {
                  ProjectState projectState;
                  try {
                    projectState = projectCache.checkedGet(project);
                  } catch (IOException e) {
                    return false;
                  }
                  if (projectState == null) {
                    throw new NoSuchProjectException(project);
                  }
                  return shouldReplicate(projectState, userProvider.get());
                }
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

  void schedule(Project.NameKey project, String ref, URIish uri, ReplicationState state) {
    schedule(project, ref, uri, state, false);
  }

  void schedule(
      Project.NameKey project, String ref, URIish uri, ReplicationState state, boolean now) {
    if (!shouldReplicate(project, ref, state)) {
      repLog.debug("Not scheduling replication {}:{} => {}", project, ref, uri);
      return;
    }
    repLog.info("scheduling replication {}:{} => {}", project, ref, uri);

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

    synchronized (stateLock) {
      PushOne task = getPendingPush(uri);
      if (task == null) {
        task = opFactory.create(project, uri);
        addRef(task, ref);
        task.addState(ref, state);
        @SuppressWarnings("unused")
        ScheduledFuture<?> ignored =
            pool.schedule(task, now ? 0 : config.getDelay(), TimeUnit.SECONDS);
        pending.put(uri, task);
      } else {
        addRef(task, ref);
        task.addState(ref, state);
      }
      state.increasePushTaskCount(project.get(), ref);
      repLog.info("scheduled {}:{} => {} to run after {}s", project, ref, task, config.getDelay());
    }
  }

  private PushOne getPendingPush(URIish uri) {
    PushOne e = pending.get(uri);
    if (e != null && !e.wasCanceled()) {
      return e;
    }
    return null;
  }

  void pushWasCanceled(PushOne pushOp) {
    synchronized (stateLock) {
      URIish uri = pushOp.getURI();
      pending.remove(uri);
    }
  }

  private void addRef(PushOne e, String ref) {
    e.addRef(ref);
    postReplicationScheduledEvent(e, ref);
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
          pendingPushOp.addRefs(pushOp.getRefs());
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

          pushOp.addRefs(pendingPushOp.getRefs());
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
            RemoteRefUpdate.Status status =
                RetryReason.REPOSITORY_MISSING.equals(reason)
                    ? NON_EXISTING
                    : REJECTED_OTHER_REASON;
            postReplicationFailedEvent(pushOp, status);
            if (pushOp.setToRetry()) {
              postReplicationScheduledEvent(pushOp);
              @SuppressWarnings("unused")
              ScheduledFuture<?> ignored2 =
                  pool.schedule(pushOp, config.getRetryDelay(), TimeUnit.MINUTES);
            } else {
              pushOp.canceledByReplication();
              pending.remove(uri);
              stateLog.error(
                  "Push to " + pushOp.getURI() + " cancelled after maximum number of retries",
                  pushOp.getStatesAsArray());
            }
            break;
        }
      }
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
      inFlight.put(op.getURI(), op);
    }
    return RunwayStatus.allowed();
  }

  void notifyFinished(PushOne task) {
    synchronized (stateLock) {
      inFlight.remove(task.getURI());
      if (!task.wasCanceled()) {
        for (String ref : task.getRefs()) {
          if (!refHasPendingPush(task.getURI(), ref)) {
            replicationTasksStorage
                .get()
                .delete(
                    new ReplicateRefUpdate(
                        task.getProjectNameKey().get(), ref, task.getURI(), getRemoteConfigName()));
          }
        }
      }
    }
  }

  private boolean refHasPendingPush(URIish opUri, String ref) {
    return pushContainsRef(pending.get(opUri), ref) || pushContainsRef(inFlight.get(opUri), ref);
  }

  private boolean pushContainsRef(PushOne op, String ref) {
    return op != null && op.getRefs().contains(ref);
  }

  boolean wouldPushProject(Project.NameKey project) {
    if (!shouldReplicate(project)) {
      repLog.debug("Skipping replication of project {}", project.get());
      return false;
    }

    // by default push all projects
    List<String> projects = config.getProjects();
    if (projects.isEmpty()) {
      return true;
    }

    boolean matches = (new ReplicationFilter(projects)).matches(project);
    if (!matches) {
      repLog.debug("Skipping replication of project {}; does not match filter", project.get());
    }
    return matches;
  }

  boolean isSingleProjectMatch() {
    List<String> projects = config.getProjects();
    boolean ret = (projects.size() == 1);
    if (ret) {
      String projectMatch = projects.get(0);
      if (ReplicationFilter.getPatternType(projectMatch)
          != ReplicationFilter.PatternType.EXACT_MATCH) {
        // projectMatch is either regular expression, or wild-card.
        //
        // Even though they might refer to a single project now, they need not
        // after new projects have been created. Hence, we do not treat them as
        // matching a single project.
        ret = false;
      }
    }
    return ret;
  }

  boolean wouldPushRef(String ref) {
    if (!config.replicatePermissions() && RefNames.REFS_CONFIG.equals(ref)) {
      repLog.debug("Skipping push of ref {}; it is a meta ref", ref);
      return false;
    }
    for (RefSpec s : config.getRemoteConfig().getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return true;
      }
    }
    repLog.debug("Skipping push of ref {}; it does not match push ref specs", ref);
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

  List<URIish> getURIs(Project.NameKey project, String urlMatch) {
    List<URIish> r = Lists.newArrayListWithCapacity(config.getRemoteConfig().getURIs().size());
    for (URIish configUri : config.getRemoteConfig().getURIs()) {
      URIish uri = getURI(configUri, project);
      if (matches(configUri, urlMatch) || matches(uri, urlMatch)) {
        r.add(uri);
      }
    }
    return r;
  }

  URIish getURI(URIish template, Project.NameKey project) {
    String name = project.get();
    if (needsUrlEncoding(template)) {
      name = encode(name);
    }
    String remoteNameStyle = config.getRemoteNameStyle();
    if (remoteNameStyle.equals("dash")) {
      name = name.replace("/", "-");
    } else if (remoteNameStyle.equals("underscore")) {
      name = name.replace("/", "_");
    } else if (remoteNameStyle.equals("basenameOnly")) {
      name = FilenameUtils.getBaseName(name);
    } else if (!remoteNameStyle.equals("slash")) {
      repLog.debug("Unknown remoteNameStyle: {}, falling back to slash", remoteNameStyle);
    }
    String replacedPath =
        ReplicationQueue.replaceName(template.getPath(), name, isSingleProjectMatch());
    return (replacedPath != null) ? template.setPath(replacedPath) : template;
  }

  static boolean needsUrlEncoding(URIish uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
        || "https".equalsIgnoreCase(uri.getScheme())
        || "amazon-s3".equalsIgnoreCase(uri.getScheme());
  }

  static String encode(String str) {
    try {
      // Some cleanup is required. The '/' character is always encoded as %2F
      // however remote servers will expect it to be not encoded as part of the
      // path used to the repository. Space is incorrectly encoded as '+' for this
      // context. In the path part of a URI space should be %20, but in form data
      // space is '+'. Our cleanup replace fixes these two issues.
      return URLEncoder.encode(str, "UTF-8").replaceAll("%2[fF]", "/").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
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

  int getLockErrorMaxRetries() {
    return config.getLockErrorMaxRetries();
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

  public int getReplicationDelaySeconds() {
    return config.getDelay() * 1000;
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

  private void postReplicationScheduledEvent(PushOne pushOp, String inputRef) {
    Set<String> refs = inputRef == null ? pushOp.getRefs() : ImmutableSet.of(inputRef);
    Project.NameKey project = pushOp.getProjectNameKey();
    String targetNode = resolveNodeName(pushOp.getURI());
    for (String ref : refs) {
      ReplicationScheduledEvent event =
          new ReplicationScheduledEvent(project.get(), ref, targetNode);
      try {
        eventDispatcher.get().postEvent(new Branch.NameKey(project, ref), event);
      } catch (PermissionBackendException e) {
        repLog.error("error posting event", e);
      }
    }
  }

  private void postReplicationFailedEvent(PushOne pushOp, RemoteRefUpdate.Status status) {
    Project.NameKey project = pushOp.getProjectNameKey();
    String targetNode = resolveNodeName(pushOp.getURI());
    for (String ref : pushOp.getRefs()) {
      RefReplicatedEvent event =
          new RefReplicatedEvent(project.get(), ref, targetNode, RefPushResult.FAILED, status);
      try {
        eventDispatcher.get().postEvent(new Branch.NameKey(project, ref), event);
      } catch (PermissionBackendException e) {
        repLog.error("error posting event", e);
      }
    }
  }
}
