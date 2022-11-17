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

import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Queues;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.util.logging.NamedFluentLogger;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.transport.URIish;

/** Manages automatic replication to remote repositories. */
public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitReferenceUpdatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {
  static final String REPLICATION_LOG_NAME = "replication_log";
  static final NamedFluentLogger repLog = NamedFluentLogger.forName(REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  private final ReplicationConfig replConfig;
  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<ReplicationDestinations> destinations; // For Guice circular dependency
  private final ReplicationTasksStorage replicationTasksStorage;
  private final ProjectDeletionState.Factory projectDeletionStateFactory;
  private volatile boolean running;
  private final AtomicBoolean replaying = new AtomicBoolean();
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private Distributor distributor;

  protected enum Prune {
    TRUE,
    FALSE;
  }

  @Inject
  ReplicationQueue(
      ReplicationConfig rc,
      WorkQueue wq,
      Provider<ReplicationDestinations> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      ReplicationTasksStorage rts,
      ProjectDeletionState.Factory pd) {
    replConfig = rc;
    workQueue = wq;
    dispatcher = dis;
    destinations = rd;
    stateLog = sl;
    replicationTasksStorage = rts;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    projectDeletionStateFactory = pd;
  }

  @Override
  public void start() {
    if (!running) {
      destinations.get().startup(workQueue);
      running = true;
      replicationTasksStorage.recoverAll();
      synchronizePendingEvents(Prune.FALSE);
      fireBeforeStartupEvents();
      distributor = new Distributor(workQueue);
    }
  }

  @Override
  public void stop() {
    running = false;
    distributor.stop();
    int discarded = destinations.get().shutdown();
    if (discarded > 0) {
      repLog.atWarning().log("Canceled %d replication events during shutdown", discarded);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isReplaying() {
    return replaying.get();
  }

  public void scheduleFullSync(
      Project.NameKey project, String urlMatch, ReplicationState state, boolean now) {
    fire(project, urlMatch, PushOne.ALL_REFS, state, now);
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    fire(event.getProjectName(), event.getRefName());
  }

  private void fire(String projectName, String refName) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), null, refName, state, false);
    state.markAllPushTasksScheduled();
  }

  private void fire(
      Project.NameKey project,
      String urlMatch,
      String refName,
      ReplicationState state,
      boolean now) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(ReferenceUpdatedEvent.create(project.get(), refName));
      return;
    }

    for (Destination cfg : destinations.get().getAll(FilterType.ALL)) {
      pushReference(cfg, project, urlMatch, refName, state, now);
    }
  }

  private void fireFromStorage(URIish uri, Project.NameKey project, String refName) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    for (Destination dest : destinations.get().getDestinations(uri, project, refName)) {
      dest.scheduleFromStorage(project, refName, uri, state);
    }
    state.markAllPushTasksScheduled();
  }

  @UsedAt(UsedAt.Project.COLLABNET)
  public void pushReference(Destination cfg, Project.NameKey project, String refName) {
    pushReference(cfg, project, null, refName, null, true);
  }

  private void pushReference(
      Destination cfg,
      Project.NameKey project,
      String urlMatch,
      String refName,
      ReplicationState state,
      boolean now) {
    boolean withoutState = state == null;
    if (withoutState) {
      state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    }
    if (cfg.wouldPushProject(project) && cfg.wouldPushRef(refName)) {
      for (URIish uri : cfg.getURIs(project, urlMatch)) {
        replicationTasksStorage.create(
            ReplicateRefUpdate.create(project.get(), refName, uri, cfg.getRemoteConfigName()));
        cfg.schedule(project, refName, uri, state, now);
      }
    } else {
      repLog.atFine().log("Skipping ref %s on project %s", refName, project.get());
    }
    if (withoutState) {
      state.markAllPushTasksScheduled();
    }
  }

  private void synchronizePendingEvents(Prune prune) {
    if (replaying.compareAndSet(false, true)) {
      final Map<ReplicateRefUpdate, String> taskNamesByReplicateRefUpdate =
          new ConcurrentHashMap<>();
      if (Prune.TRUE.equals(prune)) {
        for (Destination destination : destinations.get().getAll(FilterType.ALL)) {
          taskNamesByReplicateRefUpdate.putAll(destination.getTaskNamesByReplicateRefUpdate());
        }
      }
      new ChainedScheduler.StreamScheduler<>(
          workQueue.getDefaultQueue(),
          replicationTasksStorage.streamWaiting(),
          new ChainedScheduler.Runner<ReplicationTasksStorage.ReplicateRefUpdate>() {
            @Override
            public void run(ReplicationTasksStorage.ReplicateRefUpdate u) {
              try {
                fireFromStorage(new URIish(u.uri()), Project.nameKey(u.project()), u.ref());
                if (Prune.TRUE.equals(prune)) {
                  taskNamesByReplicateRefUpdate.remove(u);
                }
              } catch (URISyntaxException e) {
                repLog.atSevere().withCause(e).log(
                    "Encountered malformed URI for persisted event %s", u);
              } catch (Throwable e) {
                repLog.atSevere().withCause(e).log("Unexpected error while firing pending events");
              }
            }

            @Override
            public void onDone() {
              if (Prune.TRUE.equals(prune)) {
                pruneNoLongerPending(new HashSet<>(taskNamesByReplicateRefUpdate.values()));
              }
              replaying.set(false);
            }

            @Override
            public String toString(ReplicationTasksStorage.ReplicateRefUpdate u) {
              return "Scheduling push to " + String.format("%s:%s", u.project(), u.ref());
            }
          });
    }
  }

  private void pruneNoLongerPending(Set<String> prunableTaskNames) {
    // Queue tasks have wrappers around them so workQueue.getTasks() does not return the PushOnes.
    // We also cannot access them by taskId since PushOnes don't have a taskId, they do have
    // an Id, but it is not the id assigned to the task in the queues. The tasks in the queue
    // do use the same name as returned by toString() though, so that can be used to correlate
    // PushOnes with queue tasks despite their wrappers.
    for (WorkQueue.Task<?> task : workQueue.getTasks()) {
      WorkQueue.Task.State state = task.getState();
      if (state == WorkQueue.Task.State.SLEEPING || state == WorkQueue.Task.State.READY) {
        if (task instanceof WorkQueue.ProjectTask) {
          if (prunableTaskNames.contains(task.toString())) {
            repLog.atFine().log("Pruning externally completed task: %s", task);
            task.cancel(false);
          }
        }
      }
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    ProjectDeletionState state = projectDeletionStateFactory.create(p);
    Collection<Map.Entry<Destination, URIish>> projectsToDelete =
        destinations.get().getURIs(Optional.empty(), p, FilterType.PROJECT_DELETION).entries();

    projectsToDelete.forEach(e -> state.setToProcess(e.getValue()));
    projectsToDelete.forEach(e -> e.getKey().scheduleDeleteProject(e.getValue(), p, state));
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    destinations.get().getURIs(Optional.empty(), p, FilterType.ALL).entries().stream()
        .forEach(e -> e.getKey().scheduleUpdateHead(e.getValue(), p, event.getNewHeadName()));
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.atInfo().log("Firing pending task %s", event);
        fire(event.projectName(), event.refName());
        eventsReplayed.add(eventKey);
      }
    }
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(String projectName, String refName) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(projectName, refName);
    }

    public abstract String projectName();

    public abstract String refName();
  }

  protected class Distributor implements WorkQueue.CancelableRunnable {
    public ScheduledThreadPoolExecutor executor;
    public ScheduledFuture<?> future;

    public Distributor(WorkQueue wq) {
      int distributionInterval = replConfig.getDistributionInterval();
      if (distributionInterval > 0) {
        executor = wq.createQueue(1, "Replication Distribution", false);
        future =
            executor.scheduleWithFixedDelay(
                this, distributionInterval, distributionInterval, SECONDS);
      }
    }

    @Override
    public void run() {
      if (!running) {
        return;
      }
      try {
        synchronizePendingEvents(Prune.TRUE);
      } catch (Exception e) {
        repLog.atSevere().withCause(e).log("error distributing tasks");
      }
    }

    @Override
    public void cancel() {
      future.cancel(true);
    }

    public void stop() {
      if (executor != null) {
        cancel();
        executor.getQueue().remove(this);
      }
    }

    @Override
    public String toString() {
      return "Replication Distributor";
    }
  }
}
