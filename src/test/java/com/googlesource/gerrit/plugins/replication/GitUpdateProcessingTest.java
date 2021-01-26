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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicationDoneEvent;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class GitUpdateProcessingTest {
  private EventDispatcher dispatcherMock;
  private GitUpdateProcessing gitUpdateProcessing;

  @Before
  public void setUp() throws Exception {
    dispatcherMock = mock(EventDispatcher.class);
    gitUpdateProcessing = new GitUpdateProcessing(dispatcherMock);
  }

  @Test
  public void headRefReplicated() throws URISyntaxException, PermissionBackendException {
    URIish completeUri = new URIish("git://someHost/basePath/someProject.git");
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            completeUri,
            RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);

    gitUpdateProcessing.onRefReplicatedToOneNode(
        "someProject",
        "refs/heads/master",
        completeUri,
        RefPushResult.SUCCEEDED,
        RemoteRefUpdate.Status.OK);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedEvent));
  }

  @Test
  public void changeRefReplicated() throws URISyntaxException, PermissionBackendException {
    URIish completeUri = new URIish("git://someHost/basePath/someProject.git");
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent(
            "someProject",
            "refs/changes/01/1/1",
            completeUri,
            RefPushResult.FAILED,
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);

    gitUpdateProcessing.onRefReplicatedToOneNode(
        "someProject",
        "refs/changes/01/1/1",
        completeUri,
        RefPushResult.FAILED,
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedEvent));
  }

  @Test
  public void onAllNodesReplicated() throws PermissionBackendException {
    RefReplicationDoneEvent expectedDoneEvent =
        new RefReplicationDoneEvent("someProject", "refs/heads/master", 5);

    gitUpdateProcessing.onRefReplicatedToAllNodes("someProject", "refs/heads/master", 5);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedDoneEvent));
  }
}
