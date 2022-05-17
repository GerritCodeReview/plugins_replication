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
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class ReplicationStateTest {

  private ReplicationState replicationState;
  private PushResultProcessing pushResultProcessingMock;

  @Before
  public void setUp() throws Exception {
    pushResultProcessingMock = mock(PushResultProcessing.class);
    replicationState = new ReplicationState(pushResultProcessingMock);
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
    // actual test
    replicationState.markAllPushTasksScheduled();

    // expected event
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(0);
  }

  @Test
  public void shouldFireEventsForReplicationOfOneRefToOneNode() throws URISyntaxException {
    URIish uri = new URIish("git://someHost/someRepo.git");

    // actual test
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);

    // expected events
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "someRef", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "someRef", 1);
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(1);
  }

  @Test
  public void shouldFireEventsForReplicationOfOneRefToMultipleNodes() throws URISyntaxException {
    URIish uri1 = new URIish("git://someHost1/someRepo.git");
    URIish uri2 = new URIish("git://someHost2/someRepo.git");

    // actual test
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.increasePushTaskCount("someProject", "someRef");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "someRef", uri2, RefPushResult.FAILED, RemoteRefUpdate.Status.NON_EXISTING);

    // expected events
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "someRef", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject",
            "someRef",
            uri2,
            RefPushResult.FAILED,
            RemoteRefUpdate.Status.NON_EXISTING);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "someRef", 2);
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(2);
  }

  @Test
  public void shouldFireEventsForReplicationOfMultipleRefsToMultipleNodes()
      throws URISyntaxException {
    URIish uri1 = new URIish("git://host1/someRepo.git");
    URIish uri2 = new URIish("git://host2/someRepo.git");
    URIish uri3 = new URIish("git://host3/someRepo.git");

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

    // expected events
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref1", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref1", uri3, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref2", uri2, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "ref1", 3);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "ref2", 2);
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(5);
  }

  @Test
  public void shouldFireEventsForReplicationSameRefDifferentProjects() throws URISyntaxException {
    URIish uri = new URIish("git://host1/someRepo.git");

    // actual test
    replicationState.increasePushTaskCount("project1", "ref1");
    replicationState.increasePushTaskCount("project2", "ref2");
    replicationState.markAllPushTasksScheduled();
    replicationState.notifyRefReplicated(
        "project1", "ref1", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "project2", "ref2", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);

    // expected events
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "project1", "ref1", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "project2", "ref2", uri, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("project1", "ref1", 1);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("project2", "ref2", 1);
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(2);
  }

  @Test
  public void shouldFireEventsWhenSomeReplicationCompleteBeforeAllTasksAreScheduled()
      throws URISyntaxException {
    URIish uri1 = new URIish("git://host1/someRepo.git");

    // actual test
    replicationState.increasePushTaskCount("someProject", "ref1");
    replicationState.increasePushTaskCount("someProject", "ref2");
    replicationState.notifyRefReplicated(
        "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.notifyRefReplicated(
        "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    replicationState.markAllPushTasksScheduled();

    // expected events
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref1", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock)
        .onRefReplicatedToOneNode(
            "someProject", "ref2", uri1, RefPushResult.SUCCEEDED, RemoteRefUpdate.Status.OK);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "ref1", 1);
    verify(pushResultProcessingMock).onRefReplicatedToAllNodes("someProject", "ref2", 1);
    verify(pushResultProcessingMock).onAllRefsReplicatedToAllNodes(2);
  }

  @Test
  public void toStringRefPushResult() throws Exception {
    assertEquals("failed", RefPushResult.FAILED.toString());
    assertEquals("not-attempted", RefPushResult.NOT_ATTEMPTED.toString());
    assertEquals("succeeded", RefPushResult.SUCCEEDED.toString());
  }
}
