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

import com.google.common.base.Objects;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.ProjectEvent;

public class ProjectDeletionReplicationDoneEvent extends ProjectEvent {
  public static final String TYPE = "project-deletion-replication-done";

  private final String project;

  public ProjectDeletionReplicationDoneEvent(String project) {
    super(TYPE);
    this.project = project;
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProjectDeletionReplicationDoneEvent)) {
      return false;
    }
    ProjectDeletionReplicationDoneEvent that = (ProjectDeletionReplicationDoneEvent) o;
    return Objects.equal(project, that.project);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(project);
  }
}
