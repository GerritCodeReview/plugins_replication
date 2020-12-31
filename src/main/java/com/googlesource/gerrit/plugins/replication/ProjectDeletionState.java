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

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.eclipse.jgit.transport.URIish;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.resolveNodeName;

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
    statusByRemote.put(uri, ProjectDeletionStatus.SCHEDULED);
    eventDispatcher
        .get()
        .postEvent(
            project,
            new ProjectDeletionReplicationScheduledEvent(project.get(), resolveNodeName(uri)));
  }

  public void setRunning(URIish uri) {
    statusByRemote.put(uri, ProjectDeletionStatus.RUNNING);
    eventDispatcher
        .get()
        .postEvent(
            project,
            new ProjectDeletionReplicationRunningEvent(project.get(), resolveNodeName(uri)));
  }

  public void setSucceeded(URIish uri) {
    statusByRemote.put(uri, ProjectDeletionStatus.SUCCEEDED);
    eventDispatcher
        .get()
        .postEvent(
            project,
            new ProjectDeletionReplicationSucceededEvent(project.get(), resolveNodeName(uri)));
    notifyIfDoneAllNodes();
  }

  public void setFailed(URIish uri) {
    statusByRemote.put(uri, ProjectDeletionStatus.FAILED);
    eventDispatcher
        .get()
        .postEvent(
            project,
            new ProjectDeletionReplicationFailedEvent(project.get(), resolveNodeName(uri)));
    notifyIfDoneAllNodes();
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

  private enum ProjectDeletionStatus {
    SCHEDULED,
    RUNNING,
    FAILED,
    SUCCEEDED;
  }
}
