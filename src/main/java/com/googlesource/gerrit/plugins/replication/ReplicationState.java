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

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

public class ReplicationState {
  private boolean allScheduled;
  private final PushResultProcessing pushResultProcessing;

  private final Lock countingLock = new ReentrantLock();
  private final CountDownLatch allPushTasksFinished = new CountDownLatch(1);

  private static class RefReplicationStatus {
    private final String project;
    private final String ref;
    private int nodesToReplicateCount;
    private int replicatedNodesCount;

    RefReplicationStatus(String project, String ref) {
      this.project = project;
      this.ref = ref;
    }

    public boolean allDone() {
      return replicatedNodesCount == nodesToReplicateCount;
    }
  }

  private final Table<String, String, RefReplicationStatus> statusByProjectRef;
  private int totalPushTasksCount;
  private int finishedPushTasksCount;

  public ReplicationState(PushResultProcessing processing) {
    pushResultProcessing = processing;
    statusByProjectRef = HashBasedTable.create();
  }

  public void increasePushTaskCount(String project, String ref) {
    countingLock.lock();
    try {
      getRefStatus(project, ref).nodesToReplicateCount++;
      totalPushTasksCount++;
    } finally {
      countingLock.unlock();
    }
  }

  public boolean hasPushTask() {
    return totalPushTasksCount != 0;
  }

  public void notifyRefReplicated(
      String project,
      String ref,
      URIish uri,
      RefPushResult status,
      RemoteRefUpdate.Status refUpdateStatus) {
    pushResultProcessing.onRefReplicatedToOneNode(project, ref, uri, status, refUpdateStatus);

    RefReplicationStatus completedRefStatus = null;
    boolean allPushTaksCompleted = false;
    countingLock.lock();
    try {
      RefReplicationStatus refStatus = getRefStatus(project, ref);
      refStatus.replicatedNodesCount++;
      finishedPushTasksCount++;

      if (allScheduled) {
        if (refStatus.allDone()) {
          completedRefStatus = statusByProjectRef.remove(project, ref);
        }
        allPushTaksCompleted = finishedPushTasksCount == totalPushTasksCount;
      }
    } finally {
      countingLock.unlock();
    }

    if (completedRefStatus != null) {
      doRefPushTasksCompleted(completedRefStatus);
    }

    if (allPushTaksCompleted) {
      doAllPushTasksCompleted();
    }
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
    fireRemainingOnRefReplicatedToAllNodes();
    pushResultProcessing.onAllRefsReplicatedToAllNodes(totalPushTasksCount);
    allPushTasksFinished.countDown();
  }

  /**
   * Some could be remaining if replication of a ref is completed before all tasks are scheduled.
   */
  private void fireRemainingOnRefReplicatedToAllNodes() {
    for (RefReplicationStatus refStatus : statusByProjectRef.values()) {
      doRefPushTasksCompleted(refStatus);
    }
  }

  private void doRefPushTasksCompleted(RefReplicationStatus refStatus) {
    pushResultProcessing.onRefReplicatedToAllNodes(
        refStatus.project, refStatus.ref, refStatus.nodesToReplicateCount);
  }

  private RefReplicationStatus getRefStatus(String project, String ref) {
    if (!statusByProjectRef.contains(project, ref)) {
      RefReplicationStatus refStatus = new RefReplicationStatus(project, ref);
      statusByProjectRef.put(project, ref, refStatus);
      return refStatus;
    }
    return statusByProjectRef.get(project, ref);
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
    /** The ref was not successfully replicated. */
    FAILED,

    /** The ref is not configured to be replicated. */
    NOT_ATTEMPTED,

    /** The ref was successfully replicated. */
    SUCCEEDED;

    @Override
    public String toString() {
      return name().toLowerCase().replace("_", "-");
    }
  }
}
