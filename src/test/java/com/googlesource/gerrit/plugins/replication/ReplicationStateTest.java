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

import static com.google.common.truth.Truth.assertThat;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.resetToDefault;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class ReplicationStateTest {

  private ReplicationState replicationState;
  private PushResultProcessing pushResultProcessingMock;
  private EventsStorage eventsStorage;

  @Before
  public void setUp() throws Exception {
    pushResultProcessingMock = createNiceMock(PushResultProcessing.class);
    replay(pushResultProcessingMock);
    eventsStorage = createNiceMock(EventsStorage.class);
    replay(eventsStorage);
    replicationState = new ReplicationState(eventsStorage, pushResultProcessingMock);
  }

  @Test
  public void shouldNotHavePushTask() {
    assertThat(replicationState.hasPushTask()).isFalse();
  }

  @Test
  public void shouldHavePushTask() {
    replicationState.increasePushTaskCount("someProject", "someRef");
    assertThat(replicationState.hasPushTask()).isTrue();
  }

  @Test
  public void shouldFireOneReplicationEventWhenNothingToReplicate() {
    resetToDefault(pushResultProcessingMock);

    // expected event
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(0);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.markAllPushTasksScheduled();
    verify(pushResultProcessingMock);
  }

  @Test
  public void shouldFireEventsForReplicationOfOneRefToOneNode() throws URISyntaxException {
    resetToDefault(pushResultProcessingMock);
    URIish uri = new URIish("git://someHost/someRepo.git");

    // expected events
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "someRef", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "someRef", 1);
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(1);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock);
  }

  @Test
  public void shouldFireEventsForReplicationOfOneRefToMultipleNodes() throws URISyntaxException {
    resetToDefault(pushResultProcessingMock);
    URIish uri1 = new URIish("git://someHost1/someRepo.git");
    URIish uri2 = new URIish("git://someHost2/someRepo.git");

    // expected events
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "someRef", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "someRef", uri2, RefPushResult.FAILED, RemoteRefUpdate.Status.NON_EXISTING);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "someRef", 2);
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(2);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri2, RefPushResult.FAILED, RemoteRefUpdate.Status.NON_EXISTING);
    verify(pushResultProcessingMock);
  }

  @Test
  public void shouldFireEventsForReplicationOfMultipleRefsToMultipleNodes()
      throws URISyntaxException {
    resetToDefault(pushResultProcessingMock);
    URIish uri1 = new URIish("git://host1/someRepo.git");
    URIish uri2 = new URIish("git://host2/someRepo.git");
    URIish uri3 = new URIish("git://host3/someRepo.git");

    // expected events
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref1", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref1", uri3, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref2", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "ref1", 3);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "ref2", 2);
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(5);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.increasePushTaskCount("someProject", "ref1");
    replicationState.increasePushTaskCount("someProject", "ref1");
    replicationState.increasePushTaskCount("someProject", "ref1");
    replicationState.increasePushTaskCount("someProject", "ref2");
    replicationState.increasePushTaskCount("someProject", "ref2");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref1", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref1", uri3, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref2", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock);
  }

  @Test
  public void shouldFireEventsForReplicationSameRefDifferentProjects() throws URISyntaxException {
    resetToDefault(pushResultProcessingMock);
    URIish uri = new URIish("git://host1/someRepo.git");

    // expected events
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "project1", "ref1", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "project2", "ref2", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToAllNodes("project1", "ref1", 1);
    pushResultProcessingMock.onRefReplicatedToAllNodes("project2", "ref2", 1);
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(2);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.increasePushTaskCount("project1", "ref1");
    replicationState.increasePushTaskCount("project2", "ref2");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "project1", "ref1", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "project2", "ref2", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock);
  }

  @Test
  public void shouldFireEventsWhenSomeReplicationCompleteBeforeAllTasksAreScheduled()
      throws URISyntaxException {
    resetToDefault(pushResultProcessingMock);
    URIish uri1 = new URIish("git://host1/someRepo.git");

    // expected events
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToOneNode(
        "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "ref1", 1);
    pushResultProcessingMock.onRefReplicatedToAllNodes("someProject", "ref2", 1);
    pushResultProcessingMock.onAllRefsReplicatedToAllNodes(2);
    replay(pushResultProcessingMock);

    // actual test
    replicationState.increasePushTaskCount("someProject", "ref1");
    replicationState.increasePushTaskCount("someProject", "ref2");
    replicationState.notifyRefReplicated(
        "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.markAllPushTasksScheduled();
    verify(pushResultProcessingMock);
  }

  @Test
  public void toStringRefPushResult() throws Exception {
    assertEquals("failed", RefPushResult.FAILED.toString());
    assertEquals("not-attempted", RefPushResult.NOT_ATTEMPTED.toString());
    assertEquals("succeeded", RefPushResult.SUCCEEDED.toString());
  }
}
