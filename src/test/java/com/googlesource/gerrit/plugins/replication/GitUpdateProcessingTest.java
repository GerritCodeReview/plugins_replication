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

import com.google.gerrit.common.EventDispatcher;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gwtorm.client.KeyUtil;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.gwtorm.server.StandardKeyEncoder;

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import junit.framework.TestCase;

import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;

@SuppressWarnings("unchecked")
public class GitUpdateProcessingTest extends TestCase {
  static {
    KeyUtil.setEncoderImpl(new StandardKeyEncoder());
  }

  private EventDispatcher dispatcherMock;
  private ChangeNotes notesMock;
  private GitUpdateProcessing gitUpdateProcessing;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    dispatcherMock = createMock(EventDispatcher.class);
    replay(dispatcherMock);
    ReviewDb reviewDbMock = createNiceMock(ReviewDb.class);
    replay(reviewDbMock);
    SchemaFactory<ReviewDb> schemaMock = createMock(SchemaFactory.class);
    expect(schemaMock.open()).andReturn(reviewDbMock).anyTimes();
    replay(schemaMock);
    notesMock = createNiceMock(ChangeNotes.class);
    replay(notesMock);
    ChangeNotes.Factory notesFactoryMock = createMock(ChangeNotes.Factory.class);
    expect(notesFactoryMock.create(eq(reviewDbMock),
        anyObject(Project.NameKey.class), anyObject(Change.Id.class)))
            .andReturn(notesMock).anyTimes();
    replay(notesFactoryMock);
    gitUpdateProcessing =
        new GitUpdateProcessing(dispatcherMock, schemaMock, notesFactoryMock);
  }

  public void testHeadRefReplicated() throws URISyntaxException {
    reset(dispatcherMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent("someProject", "refs/heads/master", "someHost",
            RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    dispatcherMock.postEvent(anyObject(Branch.NameKey.class),
        RefReplicatedEventEquals.eqEvent(expectedEvent));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToOneNode("someProject",
        "refs/heads/master", new URIish("git://someHost/someProject.git"),
        RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(dispatcherMock);
  }

  public void testChangeRefReplicated() throws URISyntaxException, OrmException {
    Change expectedChange = new Change(null, null, null, null, null);
    reset(notesMock);
    expect(notesMock.getChange()).andReturn(expectedChange);
    replay(notesMock);

    reset(dispatcherMock);
    RefReplicatedEvent expectedEvent =
        new RefReplicatedEvent("someProject", "refs/changes/01/1/1", "someHost",
            RefPushResult.FAILED, RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
    dispatcherMock.postEvent(eq(expectedChange),
        RefReplicatedEventEquals.eqEvent(expectedEvent),
        anyObject(ReviewDb.class));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToOneNode("someProject",
        "refs/changes/01/1/1", new URIish("git://someHost/someProject.git"),
        RefPushResult.FAILED, RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD);
    verify(dispatcherMock);
  }

  public void testOnAllNodesReplicated() {
    reset(dispatcherMock);
    RefReplicationDoneEvent expectedDoneEvent =
        new RefReplicationDoneEvent("someProject", "refs/heads/master", 5);
    dispatcherMock.postEvent(anyObject(Branch.NameKey.class),
        RefReplicationDoneEventEquals.eqEvent(expectedDoneEvent));
    expectLastCall().once();
    replay(dispatcherMock);

    gitUpdateProcessing.onRefReplicatedToAllNodes("someProject", "refs/heads/master", 5);
    verify(dispatcherMock);
  }
}
