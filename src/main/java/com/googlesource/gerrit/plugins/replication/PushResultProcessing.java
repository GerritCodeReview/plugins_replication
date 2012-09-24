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

public abstract class PushResultProcessing {
  abstract void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status);

  abstract void onAllNodesReplicated(int totalPushTasksCount);

  void writeStdOut(final String message) {
    // Default doing nothing
  }

  void writeStdErr(final String message) {
    // Default doing nothing
  }

  public static class CommandProcessing extends PushResultProcessing {
    private WeakReference<StartCommand> sshCommand;
    private AtomicBoolean hasError = new AtomicBoolean();

    CommandProcessing(StartCommand sshCommand) {
      this.sshCommand = new WeakReference<StartCommand>(sshCommand);
    }

    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
      StringBuilder sb = new StringBuilder();
      sb.append("Replicate ");
      sb.append(project);
      sb.append(" to ");
      if (uri.getHost() != null) {
        sb.append(uri.getHost());
      } else {
        sb.append("localhost");
      }
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
    void onAllNodesReplicated(int totalPushTasksCount) {
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

  public static class GitUpdateProcessing extends PushResultProcessing {
    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
      //TODO: send stream events
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
      //TODO: send stream events
    }
  }

  public static class NoopProcessing extends PushResultProcessing {
    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
    }
  }
}
