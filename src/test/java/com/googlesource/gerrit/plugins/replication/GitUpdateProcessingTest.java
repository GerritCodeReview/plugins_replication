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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ChangeAccess;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gwtorm.client.KeyUtil;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.gwtorm.server.StandardKeyEncoder;

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import junit.framework.TestCase;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;

@SuppressWarnings("unchecked")
public class GitUpdateProcessingTest extends TestCase {
  static {
    KeyUtil.setEncoderImpl(new StandardKeyEncoder());
  }

  private ChangeHooks changeHooksMock;
  private ChangeAccess changeAccessMock;
  private GitUpdateProcessing gitUpdateProcessing;

  protected void setUp() throws Exception {
    super.setUp();
    changeHooksMock = createMock(ChangeHooks.class);
    replay(changeHooksMock);
    changeAccessMock = createNiceMock(ChangeAccess.class);
    replay(changeAccessMock);
    ReviewDb reviewDbMock = createNiceMock(ReviewDb.class);
    expect(reviewDbMock.changes()).andReturn(changeAccessMock).anyTimes();
    replay(reviewDbMock);
    SchemaFactory<ReviewDb> schemaMock = createMock(SchemaFactory.class);
    expect(schemaMock.open()).andReturn(reviewDbMock).anyTimes();
    replay(schemaMock);
    gitUpdateProcessing = new GitUpdateProcessing(changeHooksMock, schemaMock);
  }

  public void testHeadRefReplicated() throws URISyntaxException {
    reset(changeHooksMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent("someProject", "refs/heads/master", "someHost",
            RefPushResult.SUCCEEDED);
    changeHooksMock.postEvent(anyObject(Branch.NameKey.class),
        RefReplicatedEventEquals.eqEvent(expectedEvent));
    expectLastCall().once();
    replay(changeHooksMock);

    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    verify(changeHooksMock);
  }

  public void testChangeRefReplicated() throws URISyntaxException, OrmException {
    Change expectedChange = new Change(null, null, null, null);
    reset(changeAccessMock);
    expect(changeAccessMock.get(anyObject(Change.Id.class))).andReturn(expectedChange);
    replay(changeAccessMock);

    reset(changeHooksMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent("someProject", "refs/changes/1/1/1", "someHost",
            RefPushResult.FAILED);
    changeHooksMock.postEvent(eq(expectedChange),
        RefReplicatedEventEquals.eqEvent(expectedEvent),
        anyObject(ReviewDb.class));
    expectLastCall().once();
    replay(changeHooksMock);

    gitUpdateProcessing.onOneNodeReplicated("someProject",
        "refs/changes/1/1/1", new URIish("git://someHost/someProject.git"),
        RefPushResult.FAILED);
    verify(changeHooksMock);
  }

  public void testOnAllNodesReplicatedWithNothingReplicated() {
    gitUpdateProcessing.onAllNodesReplicated(0);
    verify(changeHooksMock);
  }

  public void testOnAllNodesReplicated() throws URISyntaxException {
    reset(changeHooksMock);
    changeHooksMock.postEvent(anyObject(Branch.NameKey.class),
        anyObject(RefReplicatedEvent.class));
    expectLastCall().times(5);
    RefReplicationDoneEvent expectedDoneEvent =
        new RefReplicationDoneEvent("someProject", "refs/heads/master", 5);
    changeHooksMock.postEvent(anyObject(Branch.NameKey.class),
        RefReplicationDoneEventEquals.eqEvent(expectedDoneEvent));
    expectLastCall().once();
    replay(changeHooksMock);

    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    gitUpdateProcessing.onOneNodeReplicated("someProject", "refs/heads/master",
        new URIish("git://someHost/someProject.git"), RefPushResult.SUCCEEDED);
    gitUpdateProcessing.onAllNodesReplicated(5);
    verify(changeHooksMock);
  }
}
