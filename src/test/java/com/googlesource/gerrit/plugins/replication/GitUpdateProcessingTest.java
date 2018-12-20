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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gwtorm.server.OrmException;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
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
    dispatcherMock = createMock(EventDispatcher.class);
    replay(dispatcherMock);
    gitUpdateProcessing = new GitUpdateProcessing(dispatcherMock);
  }

  @Test
  public void headRefReplicated()
      throws URISyntaxException, OrmException, PermissionBackendException {
    reset(dispatcherMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            "someHost",
            RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);
    dispatcherMock.postEvent(RefReplicatedEventEquals.eqEvent(expectedEvent));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToOneNode(
        "someProject",
        "refs/heads/master",
        new URIish("git://someHost/someProject.git"),
        RefPushResult.SUCCEEDED,
        RemoteRefUpdate.Status.OK);
    verify(dispatcherMock);
  }

  @Test
  public void changeRefReplicated()
      throws URISyntaxException, OrmException, PermissionBackendException {
    reset(dispatcherMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent(
            "someProject",
            "refs/changes/01/1/1",
            "someHost",
            RefPushResult.FAILED,
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
    dispatcherMock.postEvent(RefReplicatedEventEquals.eqEvent(expectedEvent));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToOneNode(
        "someProject",
        "refs/changes/01/1/1",
        new URIish("git://someHost/someProject.git"),
        RefPushResult.FAILED,
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
    verify(dispatcherMock);
  }

  @Test
  public void onAllNodesReplicated() throws OrmException, PermissionBackendException {
    reset(dispatcherMock);
    RefReplicationDoneEvent expectedDoneEvent =
        new RefReplicationDoneEvent("someProject", "refs/heads/master", 5);
    dispatcherMock.postEvent(RefReplicationDoneEventEquals.eqEvent(expectedDoneEvent));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToAllNodes("someProject", "refs/heads/master", 5);
    verify(dispatcherMock);
  }
}
