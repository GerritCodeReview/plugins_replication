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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Queues;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages automatic replication to remote repositories. */
public class ReplicationQueue
    implements LifecycleListener,
        GitReferenceUpdatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {
  static final String REPLICATION_LOG_NAME = "replication_log";
  static final Logger repLog = LoggerFactory.getLogger(REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  private final ReplicationConfig replConfig;
  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<ReplicationDestinations> destinations; // For Guice circular dependency
  private final ReplicationTasksStorage replicationTasksStorage;
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private Distributor distributor;

  @Inject
  ReplicationQueue(
      ReplicationConfig rc,
      WorkQueue wq,
      Provider<ReplicationDestinations> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      ReplicationTasksStorage rts) {
    replConfig = rc;
    workQueue = wq;
    dispatcher = dis;
    destinations = rd;
    stateLog = sl;
    replicationTasksStorage = rts;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
  }

  @Override
  public void start() {
    if (!running) {
      destinations.get().startup(workQueue);
      running = true;
      replicationTasksStorage.abortAll();
      firePendingEvents();
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
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isReplaying() {
    return replaying;
  }

  void scheduleFullSync(Project.NameKey project, String urlMatch, ReplicationState state) {
    scheduleFullSync(project, urlMatch, state, false);
  }

  @VisibleForTesting
  public void scheduleFullSync(
      Project.NameKey project, String urlMatch, ReplicationState state, boolean now) {
    fire(project, urlMatch, PushOne.ALL_REFS, state, now, false);
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    fire(event.getProjectName(), event.getRefName(), false);
  }

  private void fire(String projectName, String refName, boolean firingPending) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), null, refName, state, false, firingPending);
    state.markAllPushTasksScheduled();
  }

  private void fire(
      Project.NameKey project,
      String urlMatch,
      String refName,
      ReplicationState state,
      boolean now,
      boolean firingPending) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(ReferenceUpdatedEvent.create(project.get(), refName));
      return;
    }

    for (Destination cfg : destinations.get().getAll(FilterType.ALL)) {
      if (cfg.wouldPushProject(project) && cfg.wouldPushRef(refName)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          if (!firingPending) {
            replicationTasksStorage.create(
                new ReplicateRefUpdate(project.get(), refName, uri, cfg.getRemoteConfigName()));
          }
          cfg.schedule(project, refName, uri, state, now);
        }
      }
    }
  }

  private void firePendingEvents() {
    replaying = true;
    try {
      Set<String> eventsReplayed = new HashSet<>();
      replaying = true;
      for (ReplicationTasksStorage.ReplicateRefUpdate t : replicationTasksStorage.listWaiting()) {
        String eventKey = String.format("%s:%s", t.project, t.ref);
        if (!eventsReplayed.contains(eventKey)) {
          repLog.info("Firing pending task {}", eventKey);
          fire(t.project, t.ref, true);
          eventsReplayed.add(eventKey);
        }
      }
    } finally {
      replaying = false;
    }
  }

  private void pruneCompleted() {
    // Queue tasks have wrappers around them so use the toString() value, which will
    // include the unique task id, to identify them despite their wrappers.
    Set<String> prunable = new HashSet<>();
    for (Destination destination : destinations.get().getAll(FilterType.ALL)) {
      prunable.addAll(destination.getPrunable());
    }

    for (WorkQueue.Task<?> task : workQueue.getTasks()) {
      WorkQueue.Task.State state = task.getState();
      if (state == WorkQueue.Task.State.SLEEPING || state == WorkQueue.Task.State.READY) {
        if (task instanceof WorkQueue.ProjectTask) {
          WorkQueue.ProjectTask pt = (WorkQueue.ProjectTask) task;
          if (prunable.contains(task.toString())) {
            repLog.error("Pruning externally completed task:" + task);
            task.cancel(false);
          }
        }
      }
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    destinations.get().getURIs(Optional.empty(), p, FilterType.PROJECT_DELETION).entries().stream()
        .forEach(e -> e.getKey().scheduleDeleteProject(e.getValue(), p));
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
        repLog.info("Firing pending task {}", event);
        fire(event.projectName(), event.refName(), false);
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
        executor = wq.createQueue(1, "Replication Distribution", Thread.NORM_PRIORITY, false);
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
        firePendingEvents();
        pruneCompleted();
      } catch (Exception e) {
        repLog.error("error distributing tasks", e);
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
  }
}
