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

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.NoopProcessing;

import org.eclipse.jgit.transport.URIish;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ReplicationState {

  private boolean allScheduled;
  private final PushResultProcessing pushResultProcessing;
  private final CountDownLatch allPushTasksFinished = new CountDownLatch(1);
  private final Map<SimpleEntry<String, String>, RefReplicationInfo> replicationInfoPerProjectRef;
  private final AtomicInteger totalPushTasksCount;
  private final AtomicInteger finishedPushTasksCount;

  public ReplicationState() {
    this(new NoopProcessing());
  }

  public ReplicationState(PushResultProcessing processing) {
    pushResultProcessing = processing;
    replicationInfoPerProjectRef =
        new ConcurrentHashMap<SimpleEntry<String, String>, RefReplicationInfo>();
    totalPushTasksCount = new AtomicInteger();
    finishedPushTasksCount = new AtomicInteger();
  }

  public void increasePushTaskCount(String project, String ref) {
    getRefReplicationInfo(project, ref).nodesToReplicateCount.incrementAndGet();
    totalPushTasksCount.incrementAndGet();
  }

  public boolean hasPushTask() {
    return totalPushTasksCount.get() != 0;
  }

  public void notifyRefReplicated(String project, String ref, URIish uri,
      RefPushResult status) {
    pushResultProcessing.onRefReplicatedToOneNode(project, ref, uri, status);

    RefReplicationInfo refReplicationInfo = getRefReplicationInfo(project, ref);
    refReplicationInfo.replicatedNodesCount.incrementAndGet();
    if (allScheduled && refReplicationInfo.nodesToReplicateCount.get() == refReplicationInfo.replicatedNodesCount.get()) {
      fireOnRefReplicatedToAllNodes(project, ref);
    }

    finishedPushTasksCount.incrementAndGet();
    verifyAllPushTasksCompleted();
  }

  public void markAllPushTasksScheduled() {
    allScheduled = true;
    verifyAllPushTasksCompleted();
  }

  private void verifyAllPushTasksCompleted() {
    if (allScheduled && finishedPushTasksCount.get() == totalPushTasksCount.get()) {
      fireRemainingOnRefReplicatedToAllNodes();
      pushResultProcessing.onAllRefsReplicatedToAllNodes(totalPushTasksCount.get());
      allPushTasksFinished.countDown();
    }
  }

  private void fireRemainingOnRefReplicatedToAllNodes(){
    //Some could be remaining if replication of a ref is completed before all tasks are scheduled
    for (SimpleEntry<String, String> projectRefKey : replicationInfoPerProjectRef.keySet()) {
      fireOnRefReplicatedToAllNodes(projectRefKey.getKey(), projectRefKey.getValue());
    }
  }

  private void fireOnRefReplicatedToAllNodes(String project, String ref) {
    SimpleEntry<String, String> projectRefKey = new SimpleEntry<String, String>(project, ref);
    RefReplicationInfo refReplicationInfo = null;
    refReplicationInfo = replicationInfoPerProjectRef.remove(projectRefKey);
    if (refReplicationInfo!=null) {
      pushResultProcessing.onRefReplicatedToAllNodes(
          refReplicationInfo.project, refReplicationInfo.ref,
          refReplicationInfo.nodesToReplicateCount.get());
    }
  }

  private RefReplicationInfo getRefReplicationInfo(String project, String ref) {
    SimpleEntry<String, String> projectRefKey = new SimpleEntry<String, String>(project, ref);
    if (!replicationInfoPerProjectRef.containsKey(projectRefKey)) {
      RefReplicationInfo refReplicationInfo = new RefReplicationInfo(project, ref);
      replicationInfoPerProjectRef.put(projectRefKey, refReplicationInfo);
      return refReplicationInfo;
    }
    return replicationInfoPerProjectRef.get(projectRefKey);
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

  private class RefReplicationInfo {
    private final String project;
    private final String ref;
    private final AtomicInteger nodesToReplicateCount;
    private final AtomicInteger replicatedNodesCount;

    public RefReplicationInfo(String project, String ref) {
      this.project = project;
      this.ref = ref;
      nodesToReplicateCount = new AtomicInteger();
      replicatedNodesCount = new AtomicInteger();
    }
  }
}
