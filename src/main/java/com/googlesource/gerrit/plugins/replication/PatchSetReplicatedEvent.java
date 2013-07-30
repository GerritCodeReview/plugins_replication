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

package com.googlesource.gerrit.plugins.replication;

import com.google.gerrit.server.events.ChangeEvent;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.URIish;

public class PatchSetReplicatedEvent extends ChangeEvent {
  public final String type = "patchset-replicated";
  public final String subtype = "oneNode";
  public ReplicationAttribute replicationInfo;

  public PatchSetReplicatedEvent(String project, String ref, URIish uri,
      RefPushResult status) {
    replicationInfo = new ReplicationAttribute(uri.getHost(), project,
        ref, toStatusString(status));
  }

  private String toStatusString(RefPushResult status) {
    switch (status) {
      case SUCCEEDED:
        return "succeeded";
      case FAILED:
        return "failed";
      case NOT_ATTEMPTED:
        return "not-attempted";
      default:
        return "unknown";
    }
  }

  @SuppressWarnings("unused")
  private static class ReplicationAttribute {
    public String targetNode;
    public String project;
    public String reference;
    public String status;

    ReplicationAttribute(String targetNode, String project, String reference,
        String status) {
      this.targetNode = targetNode;
      this.project = project;
      this.reference = reference;
      this.status = status;
    }
  }
}
