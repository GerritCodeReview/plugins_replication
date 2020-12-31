// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.events;

import static com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState.ProjectDeletionStatus.FAILED;
import static com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState.ProjectDeletionStatus.SCHEDULED;
import static com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState.ProjectDeletionStatus.SUCCEEDED;
import static com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState.ProjectDeletionStatus.TO_PROCESS;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.jgit.transport.URIish;

public class ProjectDeletionState {
  public interface Factory {
    ProjectDeletionState create(Project.NameKey project);
  }

  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Project.NameKey project;
  private final ConcurrentMap<URIish, ProjectDeletionStatus> statusByURI =
      new ConcurrentHashMap<>();

  @Inject
  public ProjectDeletionState(
      DynamicItem<EventDispatcher> eventDispatcher, @Assisted Project.NameKey project) {
    this.eventDispatcher = eventDispatcher;
    this.project = project;
  }

  public void setToProcess(URIish uri) {
    statusByURI.put(uri, TO_PROCESS);
  }

  public void setScheduled(URIish uri) {
    setStatusAndBroadcastEvent(
        uri, SCHEDULED, new ProjectDeletionReplicationScheduledEvent(project.get(), uri));
  }

  public void setSucceeded(URIish uri) {
    setStatusAndBroadcastEvent(
        uri, SUCCEEDED, new ProjectDeletionReplicationSucceededEvent(project.get(), uri));
    notifyIfDeletionDoneOnAllNodes();
  }

  public void setFailed(URIish uri) {
    setStatusAndBroadcastEvent(
        uri, FAILED, new ProjectDeletionReplicationFailedEvent(project.get(), uri));
    notifyIfDeletionDoneOnAllNodes();
  }

  private void setStatusAndBroadcastEvent(
      URIish uri, ProjectDeletionStatus status, ProjectEvent event) {
    statusByURI.put(uri, status);
    eventDispatcher.get().postEvent(project, event);
  }

  public void notifyIfDeletionDoneOnAllNodes() {
    synchronized (statusByURI) {
      if (!statusByURI.isEmpty()
          && statusByURI.values().stream()
              .noneMatch(s -> s.equals(TO_PROCESS) || s.equals(SCHEDULED))) {

        statusByURI.clear();
        eventDispatcher
            .get()
            .postEvent(project, new ProjectDeletionReplicationDoneEvent(project.get()));
      }
    }
  }

  public enum ProjectDeletionStatus {
    TO_PROCESS,
    SCHEDULED,
    FAILED,
    SUCCEEDED;
  }
}
