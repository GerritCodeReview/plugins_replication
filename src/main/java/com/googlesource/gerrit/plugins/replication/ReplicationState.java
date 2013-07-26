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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.CommandProcessing;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.NoopProcessing;

public class ReplicationState {
  private boolean allScheduled;
  private final PushResultProcessing pushResultProcessing;

  private final Lock countingLock = new ReentrantLock();
  private final CountDownLatch allPushTasksFinished = new CountDownLatch(1);

  private int totalPushTasksCount;
  private int finishedPushTasksCount;

  public ReplicationState(ReplicationType type) {
    this(type, null);
  }

  public ReplicationState(ReplicationType type, StartCommand sshCommand) {
    switch(type) {
      case COMMAND:
        pushResultProcessing = new CommandProcessing(sshCommand);
        break;
      case GIT_UPDATED:
        pushResultProcessing = new GitUpdateProcessing();
        break;
      case STARTUP:
      default:
        pushResultProcessing = new NoopProcessing();
        break;
    }
  }

  public void increasePushTaskCount() {
    countingLock.lock();
    try {
      totalPushTasksCount++;
    } finally {
      countingLock.unlock();
    }
  }

  public boolean hasPushTask() {
    return totalPushTasksCount != 0;
  }

  public void notifyRefReplicated(String project, String ref, URIish uri,
      RefPushResult status) {
    pushResultProcessing.onOneNodeReplicated(project, ref, uri, status);

    countingLock.lock();
    try {
      finishedPushTasksCount++;
      if (!allScheduled) {
        return;
      }
      if (finishedPushTasksCount < totalPushTasksCount) {
        return;
      }
    } finally {
      countingLock.unlock();
    }

    doAllPushTasksCompleted();
  }

  public void markAllPushTasksScheduled() {
    countingLock.lock();
    try {
      allScheduled = true;
      if (finishedPushTasksCount < totalPushTasksCount) {
        return;
      }
    } finally {
      countingLock.unlock();
    }

    doAllPushTasksCompleted();
  }

  private void doAllPushTasksCompleted() {
    pushResultProcessing.onAllNodesReplicated(totalPushTasksCount);
    allPushTasksFinished.countDown();
  }

  public void waitForReplication() throws InterruptedException {
    allPushTasksFinished.await();
  }

  public void writeStdOut(final String message) {
    pushResultProcessing.writeStdOut(message);
  }

  public void writeStdErr(final String message) {
    pushResultProcessing.writeStdErr(message);
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
