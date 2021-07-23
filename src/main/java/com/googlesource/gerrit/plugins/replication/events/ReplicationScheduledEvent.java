// Copyright (C) 2016 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.resolveNodeName;

import com.google.gerrit.entities.Project;
import java.util.Objects;
import org.eclipse.jgit.transport.URIish;

public class ReplicationScheduledEvent extends RemoteReplicationRefEvent {
  public static final String TYPE = "ref-replication-scheduled";

  @Deprecated public final String targetNode;

  public ReplicationScheduledEvent(String project, String ref, URIish targetUri) {
    super(TYPE, project, ref, targetUri, null);
    this.targetNode = resolveNodeName(targetUri);
  }

  @Override
  public String getRefName() {
    return ref;
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ReplicationScheduledEvent)) {
      return false;
    }
    ReplicationScheduledEvent event = (ReplicationScheduledEvent) other;
    if (!Objects.equals(event.project, this.project)) {
      return false;
    }
    if (!Objects.equals(event.ref, this.ref)) {
      return false;
    }
    if (!Objects.equals(event.targetNode, this.targetNode)) {
      return false;
    }
    if (!Objects.equals(event.targetUri, this.targetUri)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
