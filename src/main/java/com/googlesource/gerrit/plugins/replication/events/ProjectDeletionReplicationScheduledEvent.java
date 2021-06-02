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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.ProjectEvent;
import org.eclipse.jgit.transport.URIish;

public class ProjectDeletionReplicationScheduledEvent extends ProjectEvent {
  public static final String TYPE = "project-deletion-replication-scheduled";

  private final String project;
  private final String targetUri;

  public ProjectDeletionReplicationScheduledEvent(String project, URIish targetUri) {
    super(TYPE);
    this.project = project;
    this.targetUri = targetUri.toASCIIString();
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @VisibleForTesting
  public String getTargetUri() {
    return targetUri;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProjectDeletionReplicationScheduledEvent)) {
      return false;
    }
    ProjectDeletionReplicationScheduledEvent that = (ProjectDeletionReplicationScheduledEvent) o;
    return Objects.equal(project, that.project) && Objects.equal(targetUri, that.targetUri);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(project, targetUri);
  }
}
