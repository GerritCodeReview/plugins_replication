// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.events;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

public class EventFieldsTest {
  @SuppressWarnings("deprecation")
  @Test
  public void refReplicatedEventFields() throws URISyntaxException {
    URIish completeUri = new URIish("git://someHost:9417/basePath/someProject.git");
    RefReplicatedEvent event =
        new RefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            completeUri,
            RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);

    assertEquals("someProject", event.project);
    assertEquals("refs/heads/master", event.ref);
    assertEquals("someHost:9417", event.targetNode);
    assertEquals("git://someHost:9417/basePath/someProject.git", event.targetUri);
    assertEquals("succeeded", event.status);
    assertEquals(RemoteRefUpdate.Status.OK, event.refStatus);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void scheduledEventFields() throws URISyntaxException {
    URIish completeUri = new URIish("git://someHost:9417/basePath/someProject.git");
    ReplicationScheduledEvent event =
        new ReplicationScheduledEvent("someProject", "refs/heads/master", completeUri);

    assertEquals("someProject", event.project);
    assertEquals("git://someHost:9417/basePath/someProject.git", event.targetUri);
    assertEquals("someHost:9417", event.targetNode);
  }

  @Test
  public void refReplicatedEventsEqual() throws URISyntaxException {
    URIish completeUri = new URIish("git://someHost:9417/basePath/someProject.git");
    RefReplicatedEvent first =
        new RefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            completeUri,
            RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);

    RefReplicatedEvent second =
        new RefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            completeUri,
            RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);

    assertEquals(first, second);
  }
}
