// Copyright (C) 2012 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.ProjectDeletionState.ProjectDeletionStatus.FAILED;
import static com.googlesource.gerrit.plugins.replication.ProjectDeletionState.ProjectDeletionStatus.RUNNING;
import static com.googlesource.gerrit.plugins.replication.ProjectDeletionState.ProjectDeletionStatus.SCHEDULED;
import static com.googlesource.gerrit.plugins.replication.ProjectDeletionState.ProjectDeletionStatus.SUCCEEDED;
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.resolveNodeName;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.jgit.transport.URIish;

public class ProjectDeletionState {
  interface Factory {
    ProjectDeletionState create(Project.NameKey project);
  }

  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Project.NameKey project;
  private final ConcurrentMap<URIish, ProjectDeletionStatus> statusByRemote;

  @Inject
  public ProjectDeletionState(
      DynamicItem<EventDispatcher> eventDispatcher, @Assisted Project.NameKey project) {
    this.eventDispatcher = eventDispatcher;
    this.project = project;
    this.statusByRemote = new ConcurrentHashMap<>();
  }

  public void setScheduled(URIish uri) {
    setStatus(
        uri,
        SCHEDULED,
        new ProjectDeletionReplicationScheduledEvent(project.get(), resolveNodeName(uri)));
  }

  public void setRunning(URIish uri) {
    setStatus(
        uri,
        RUNNING,
        new ProjectDeletionReplicationRunningEvent(project.get(), resolveNodeName(uri)));
  }

  public void setSucceeded(URIish uri) {
    setStatus(
        uri,
        SUCCEEDED,
        new ProjectDeletionReplicationSucceededEvent(project.get(), resolveNodeName(uri)));
    notifyIfDoneAllNodes();
  }

  public void setFailed(URIish uri) {
    setStatus(
        uri,
        FAILED,
        new ProjectDeletionReplicationFailedEvent(project.get(), resolveNodeName(uri)));
    notifyIfDoneAllNodes();
  }

  private void setStatus(URIish uri, ProjectDeletionStatus status, ProjectEvent event) {
    statusByRemote.put(uri, status);
    eventDispatcher.get().postEvent(project, event);
  }

  public void notifyIfDoneAllNodes() {
    synchronized (statusByRemote) {
      if (!statusByRemote.isEmpty()
          && statusByRemote.values().stream()
              .noneMatch(
                  s ->
                      s.equals(ProjectDeletionStatus.RUNNING)
                          || s.equals(ProjectDeletionStatus.SCHEDULED))) {

        statusByRemote.clear();
        eventDispatcher
            .get()
            .postEvent(project, new ProjectDeletionReplicationDoneEvent(project.get()));
      }
    }
  }

  public enum ProjectDeletionStatus {
    SCHEDULED,
    RUNNING,
    FAILED,
    SUCCEEDED;
  }
}
