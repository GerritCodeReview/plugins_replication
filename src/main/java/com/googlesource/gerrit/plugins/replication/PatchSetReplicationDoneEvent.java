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

public class PatchSetReplicationDoneEvent extends ChangeEvent {
  public final String type = "patchset-replicated";
  public final String subtype = "allNodes";
  public ReplicationAttribute replicationInfo;

  public PatchSetReplicationDoneEvent(String project, String reference, int nodesCount) {
    replicationInfo = new ReplicationAttribute();
    replicationInfo.project = project;
    replicationInfo.reference = reference;
    replicationInfo.nodesCount = nodesCount;
  }

  @SuppressWarnings("unused")
  private static class ReplicationAttribute {
    public String project;
    public String reference;
    public int nodesCount;
  }
}
