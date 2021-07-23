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

import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.resolveNodeName;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import java.util.Objects;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.URIish;

public class RefReplicatedEvent extends RemoteRefReplicationEvent {
  public static final String TYPE = "ref-replicated";

  @Deprecated public final String targetNode;
  public final Status refStatus;

  public RefReplicatedEvent(
      String project,
      String ref,
      URIish targetUri,
      RefPushResult status,
      RemoteRefUpdate.Status refStatus) {
    super(TYPE, project, ref, targetUri, status.toString());
    this.targetNode = resolveNodeName(targetUri);
    this.refStatus = refStatus;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof RefReplicatedEvent)) {
      return false;
    }
    RefReplicatedEvent event = (RefReplicatedEvent) other;
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
    if (!Objects.equals(event.status, this.status)) {
      return false;
    }
    if (!Objects.equals(event.refStatus, this.refStatus)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
