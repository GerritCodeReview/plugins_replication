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

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.NoopProcessing;

import org.eclipse.jgit.transport.URIish;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ReplicationState {

  private boolean allScheduled;
  private final PushResultProcessing pushResultProcessing;
  private final Lock countingLock = new ReentrantLock();
  private final CountDownLatch allPushTasksFinished = new CountDownLatch(1);
  private static class RefReplicationInfo {
    private final String project;
    private final String ref;
    private int nodesToReplicateCount;
    private int replicatedNodesCount;

    public RefReplicationInfo(String project, String ref) {
      this.project = project;
      this.ref = ref;
    }
  }
  private final Table<String, String, RefReplicationInfo> replicationInfoPerProjectRef;
  private int totalPushTasksCount;
  private int finishedPushTasksCount;

  public ReplicationState() {
    this(new NoopProcessing());
  }

  public ReplicationState(PushResultProcessing processing) {
    pushResultProcessing = processing;
    replicationInfoPerProjectRef = HashBasedTable.create();
  }

  public void increasePushTaskCount(String project, String ref) {
    countingLock.lock();
    try {
      getRefReplicationInfo(project, ref).nodesToReplicateCount++;
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
    pushResultProcessing.onRefReplicatedToOneNode(project, ref, uri, status);

    RefReplicationInfo completedRefRepInfo = null;
    boolean allPushTaksCompleted = false;
    countingLock.lock();
    try {
      RefReplicationInfo refRepInfo = getRefReplicationInfo(project, ref);
      refRepInfo.replicatedNodesCount++;
      finishedPushTasksCount++;

      if (allScheduled) {
        if (refRepInfo.nodesToReplicateCount == refRepInfo.replicatedNodesCount) {
          completedRefRepInfo = replicationInfoPerProjectRef.remove(project, ref);
        }
        if (finishedPushTasksCount == totalPushTasksCount) {
          allPushTaksCompleted = true;
        }
      }
    } finally {
      countingLock.unlock();
    }

    if (completedRefRepInfo!=null) {
      doRefPushTasksCompleted(completedRefRepInfo);
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

  private void fireRemainingOnRefReplicatedToAllNodes(){
    //Some coulb be remaining if replication of a ref is completed before all
    //tasks are scheduled
    for (RefReplicationInfo refRepInfo : replicationInfoPerProjectRef.values()) {
      pushResultProcessing.onRefReplicatedToAllNodes(
          refRepInfo.project, refRepInfo.ref,
          refRepInfo.nodesToReplicateCount);
    }
  }

  private void doRefPushTasksCompleted(RefReplicationInfo refRepInfo) {
    pushResultProcessing.onRefReplicatedToAllNodes(refRepInfo.project,
        refRepInfo.ref, refRepInfo.nodesToReplicateCount);
  }

  private RefReplicationInfo getRefReplicationInfo(String project, String ref) {
    if (!replicationInfoPerProjectRef.contains(project, ref)) {
      RefReplicationInfo refRepInfo = new RefReplicationInfo(project, ref);
      replicationInfoPerProjectRef.put(project, ref, refRepInfo);
      return refRepInfo;
    }
    return replicationInfoPerProjectRef.get(project, ref);
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
