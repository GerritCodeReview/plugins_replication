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

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.util.Optional;
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

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final ReplicationConfig config;
  private final ReplicationState.Factory replicationStateFactory;
  private final EventsStorage eventsStorage;
  private volatile boolean running;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      ReplicationConfig rc,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListener sl,
      ReplicationState.Factory rsf,
      EventsStorage es) {
    workQueue = wq;
    dispatcher = dis;
    config = rc;
    stateLog = sl;
    replicationStateFactory = rsf;
    eventsStorage = es;
  }

  @Override
  public void start() {
    config.startup(workQueue);
    running = true;
    firePendingEvents();
  }

  @Override
  public void stop() {
    running = false;
    int discarded = config.shutdown();
    if (discarded > 0) {
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
  }

  void scheduleFullSync(Project.NameKey project, String urlMatch, ReplicationState state) {
    scheduleFullSync(project, urlMatch, state, false);
  }

  void scheduleFullSync(
      Project.NameKey project, String urlMatch, ReplicationState state, boolean now) {
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event", state);
      return;
    }

    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          cfg.schedule(project, PushOne.ALL_REFS, uri, state, now);
        }
      }
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    onGitReferenceUpdated(event.getProjectName(), event.getRefName());
  }

  private void onGitReferenceUpdated(String projectName, String refName) {
    ReplicationState state =
        replicationStateFactory.create(new GitUpdateProcessing(dispatcher.get()));
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event", state);
      return;
    }

    Project.NameKey project = new Project.NameKey(projectName);
    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project) && cfg.wouldPushRef(refName)) {
        String eventKey = eventsStorage.persist(projectName, refName);
        state.setEventKey(eventKey);
        for (URIish uri : cfg.getURIs(project, null)) {
          cfg.schedule(project, refName, uri, state);
        }
      }
    }
    state.markAllPushTasksScheduled();
  }

  private void firePendingEvents() {
    for (EventsStorage.ReplicateRefUpdate e : eventsStorage.list()) {
      repLog.info("Firing pending event {}", e);
      onGitReferenceUpdated(e.project, e.ref);
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey p = new Project.NameKey(event.getProjectName());
    config
        .getURIs(Optional.empty(), p, FilterType.PROJECT_DELETION)
        .entries()
        .stream()
        .forEach(e -> e.getKey().scheduleDeleteProject(e.getValue(), p));
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = new Project.NameKey(event.getProjectName());
    config
        .getURIs(Optional.empty(), p, FilterType.ALL)
        .entries()
        .stream()
        .forEach(e -> e.getKey().scheduleUpdateHead(e.getValue(), p, event.getNewHeadName()));
  }
}
