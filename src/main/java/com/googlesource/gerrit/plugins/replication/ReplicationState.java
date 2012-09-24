// Copyright (C) 2012 The Android Open Source Project
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

import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ReplicationState {
  private static final Logger log = LoggerFactory.getLogger(ReplicationState.class);

  private final ReplicationType type;
  private boolean allScheduled;
  private PushProcessing pushProcessing;

  private Lock taskLock = new ReentrantLock();
  private CountDownLatch allTaskFinished = new CountDownLatch(1);

  private int totalPushCount;
  private int finishedPushCount;

  public ReplicationState(ReplicationType type) {
    this(null, type);
  }

  public ReplicationState(StartCommand sshCommand, ReplicationType type) {
    this.type = type;
    if (type == ReplicationType.COMMAND) {
      pushProcessing = new CommandProcessing(sshCommand);
    } else if (type == ReplicationType.GIT_UPDATED) {
      pushProcessing = new GitUpdateProcessing();
    } else {
      pushProcessing = new AutoStartProcessing();
    }
  }

  public void addPushCount(int count) {
    taskLock.lock();
    try {
    totalPushCount += count;
    } finally {
      taskLock.unlock();
    }
  }

  public boolean hasPushTask() {
    taskLock.lock();
    try {
      if (totalPushCount == 0) {
        return false;
      }
    } finally {
      taskLock.unlock();
    }
    return true;
  }

  public void notifyRefReplicated(String project, String ref, URIish uri, RefPushResult status) {
    pushProcessing.onOneNodeReplicated(project, ref, uri, status);

    taskLock.lock();
    try {
      finishedPushCount++;
      if (!allScheduled) {
        return;
      }
      if (finishedPushCount < totalPushCount) {
        return;
      }
    } finally {
      taskLock.unlock();
    }

    pushProcessing.onAllNodesReplicated();

    if (type == ReplicationType.COMMAND) {
      allTaskFinished.countDown();
    }
  }

  public void allTaskScheduled() {
    taskLock.lock();
    try {
      allScheduled = true;
      if (totalPushCount == 0 || finishedPushCount < totalPushCount) {
        return;
      }
    } finally {
      taskLock.unlock();
    }

    pushProcessing.onAllNodesReplicated();
  }

  public void waitForReplication() {
    taskLock.lock();
    try {
      if (finishedPushCount == totalPushCount) {
        return;
      }
    } finally {
      taskLock.unlock();
    }

    try {
      allTaskFinished.await();
    } catch (InterruptedException e) {
      log.error("It is interrupted while waiting replication to be completed");;
    }
  }

  public void writeStdOut(final String message) {
    pushProcessing.writeStdOut(message);
  }

  public void writeStdErr(final String message) {
    pushProcessing.writeStdErr(message);
  }

  public enum RefPushResult {
    /**
     * The ref is not replicated to slave.
     */
    FAILED,

    /**
     * The ref is not configured to be replicated.
     */
    NOT_ATTEMPTED,

    /**
     * ref was successfully replicated.
     */
    SUCCEEDED;
  }

  abstract class PushProcessing {
    abstract void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status);

    abstract void onAllNodesReplicated();

    void writeStdOut(final String message) {
      // Default doing nothing
    }

    void writeStdErr(final String message) {
      // Default doing nothing
    }
  }

  class CommandProcessing extends PushProcessing {
    private WeakReference<StartCommand> sshCommand;
    private boolean hasError;

    CommandProcessing(StartCommand sshCommand) {
      this.sshCommand = new WeakReference<StartCommand>(sshCommand);
    }

    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status) {
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
          sb.append("SUCCEEDED!");
          break;
        case FAILED:
          sb.append("FAILED!");
          hasError = true;
          break;
        default:
          sb.append("NOT ATTEMPTED!");
          break;
      }
      writeStdOut(sb.toString());
    }

    @Override
    void onAllNodesReplicated() {
      writeStdOut("----------------------------------------------");
      if (hasError) {
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

  class GitUpdateProcessing extends PushProcessing {
    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status) {
      //TODO: send stream events
    }

    @Override
    void onAllNodesReplicated() {
      //TODO: send stream events
    }
  }

  class AutoStartProcessing extends PushProcessing {
    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status) {
    }

    @Override
    void onAllNodesReplicated() {
    }
  }
}
