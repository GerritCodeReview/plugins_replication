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

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandProcessing extends PushResultProcessing {
  private WeakReference<StartCommand> sshCommand;
  private AtomicBoolean hasError = new AtomicBoolean();

  CommandProcessing(StartCommand sshCommand) {
    this.sshCommand = new WeakReference<StartCommand>(sshCommand);
  }

  @Override
  void onRefReplicatedToOneNode(String project, String ref, URIish uri,
      RefPushResult status) {
    StringBuilder sb = new StringBuilder();
    sb.append("Replicate ");
    sb.append(project);
    sb.append(" ref ");
    sb.append(ref);
    sb.append(" to ");
    sb.append(resolveNodeName(uri));
    sb.append(", ");
    switch (status) {
      case SUCCEEDED:
        sb.append("Succeeded!");
        break;
      case FAILED:
        sb.append("FAILED!");
        hasError.compareAndSet(false, true);
        break;
      case NOT_ATTEMPTED:
        sb.append("NOT ATTEMPTED!");
        break;
      default:
        sb.append("UNKNOWN RESULT!");
        break;
    }
    writeStdOut(sb.toString());
  }

  @Override
  void onRefReplicatedToAllNodes(String project, String ref, int nodesCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("Replication of ");
    sb.append(project);
    sb.append(" ref ");
    sb.append(ref);
    sb.append(" completed to ");
    sb.append(nodesCount);
    sb.append(" nodes, ");
    writeStdOut(sb.toString());
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

  @Override
  void writeStdOut(final String message) {
    StartCommand command = sshCommand.get();
    if (command != null) {
      command.writeStdOutSync(message);
    }
  }

  @Override
  void writeStdErr(final String message) {
    StartCommand command = sshCommand.get();
    if (command != null) {
      command.writeStdErrSync(message);
    }
  }
}