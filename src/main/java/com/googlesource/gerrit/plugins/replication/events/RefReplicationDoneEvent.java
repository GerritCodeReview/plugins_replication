// Copyright (C) 2013 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.RefEvent;
import java.util.Objects;

public class RefReplicationDoneEvent extends RefEvent {
  public static final String TYPE = "ref-replication-done";

  final String project;
  final String ref;
  final int nodesCount;

  public RefReplicationDoneEvent(String project, String ref, int nodesCount) {
    super(TYPE);
    this.project = project;
    this.ref = ref;
    this.nodesCount = nodesCount;
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @Override
  public String getRefName() {
    return ref;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof RefReplicationDoneEvent)) {
      return false;
    }

    RefReplicationDoneEvent event = (RefReplicationDoneEvent) other;
    if (!Objects.equals(event.project, this.project)) {
      return false;
    }
    if (!Objects.equals(event.ref, this.ref)) {
      return false;
    }
    if (event.nodesCount != this.nodesCount) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
