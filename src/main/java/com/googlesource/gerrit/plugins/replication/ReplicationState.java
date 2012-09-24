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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.CommandProcessing;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.NoopProcessing;

public class ReplicationState {
  private static final Logger log = LoggerFactory.getLogger(ReplicationState.class);

  private final ReplicationType type;
  private boolean allScheduled;
  private final PushResultProcessing pushProcessing;

  private final Lock countingLock = new ReentrantLock();
  private final CountDownLatch allPushTasksFinished = new CountDownLatch(1);

  private int totalPushCount;
  private int finishedPushCount;

  public ReplicationState(ReplicationType type) {
    this(type, null);
  }

  public ReplicationState(ReplicationType type, StartCommand sshCommand) {
    this.type = type;
    switch(type) {
      case COMMAND:
        pushProcessing = new CommandProcessing(sshCommand);
        break;
      case GIT_UPDATED:
        pushProcessing = new GitUpdateProcessing();
        break;
      case STARTUP:
      default:
        pushProcessing = new NoopProcessing();
        break;
    }
  }

  public void increasePushTaskCount() {
    countingLock.lock();
    try {
      totalPushCount++;
    } finally {
      countingLock.unlock();
    }
  }

  public boolean hasPushTask() {
    return totalPushCount != 0;
  }

  public void notifyRefReplicated(String project, String ref, URIish uri, RefPushResult status) {
    pushProcessing.onOneNodeReplicated(project, ref, uri, status);

    countingLock.lock();
    try {
      finishedPushCount++;
      if (!allScheduled) {
        return;
      }
      if (finishedPushCount < totalPushCount) {
        return;
      }
    } finally {
      countingLock.unlock();
    }

    pushProcessing.onAllNodesReplicated();

    if (type == ReplicationType.COMMAND) {
      allPushTasksFinished.countDown();
    }
  }

  public void markAllPushTasksScheduled() {
    countingLock.lock();
    try {
      allScheduled = true;
      if (finishedPushCount != totalPushCount) {
        return;
      } else {
        if (type == ReplicationType.COMMAND) {
          allPushTasksFinished.countDown();
        }
        if (totalPushCount == 0) {
          // In this situation, we don't trigger onAllNodesReplicated event
          return;
        }
      }
    } finally {
      countingLock.unlock();
    }

    pushProcessing.onAllNodesReplicated();
  }

  public void waitForReplication() {
    try {
      allPushTasksFinished.await();
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
}
