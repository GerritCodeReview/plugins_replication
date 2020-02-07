// Copyright (C) 2020 The Android Open Source Project
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

public class ServletCommandProcessing extends PushResultProcessing {

  ServletCommandProcessing() {}

  private AtomicBoolean hasError = new AtomicBoolean();

  @Override
  void onRefReplicatedToOneNode(
      String project,
      String ref,
      URIish uri,
      RefPushResult status,
      RemoteRefUpdate.Status refStatus) {
    String result =
        WriteHelper.onRefReplicatedToOneNode(hasError, project, ref, uri, status, refStatus);
    writeStdOut(result);
  }

  @Override
  void onRefReplicatedToAllNodes(String project, String ref, int nodesCount) {
    String result = WriteHelper.onRefReplicatedToAllNodes(project, ref, nodesCount);
    writeStdOut(result);
  }

  @Override
  void onAllRefsReplicatedToAllNodes(int totalPushTasksCount) {
    if (totalPushTasksCount == 0) {
      return;
    }
    writeStdOut("----------------------------------------------");
    if (hasError.get()) {
      writeStdOut("Replication completed with some errors!");
    } else {
      writeStdOut("Replication completed successfully!");
    }
  }
}
