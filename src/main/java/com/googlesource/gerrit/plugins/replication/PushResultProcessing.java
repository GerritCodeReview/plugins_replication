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

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.URIish;

public abstract class PushResultProcessing {

  abstract void onRefReplicatedToOneNode(String project, String ref, URIish uri, RefPushResult status);

  abstract void onRefReplicatedToAllNodes(String project, String ref, int nodesCount);

  abstract void onAllRefsReplicatedToAllNodes(int totalPushTasksCount);

  void writeStdOut(final String message) {
    // Default doing nothing
  }

  void writeStdErr(final String message) {
    // Default doing nothing
  }

  protected static String resolveNodeName(URIish uri) {
    StringBuilder sb = new StringBuilder();
    if (uri.isRemote()) {
      sb.append(uri.getHost());
      if (uri.getPort() != -1) {
        sb.append(":");
        sb.append(uri.getPort());
      }
    } else {
      sb.append(uri.getPath());
    }
    return sb.toString();
  }
}
