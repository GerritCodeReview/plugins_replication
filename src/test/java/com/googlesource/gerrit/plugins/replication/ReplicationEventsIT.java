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

package com.googlesource.gerrit.plugins.replication;

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import org.junit.Before;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationEventsIT extends ReplicationDaemon {

  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  @Inject private DynamicItem<EventDispatcher> eventDispatcher;
  TestDispatcher testDispatcher = new TestDispatcher();

  @Before
  public void setup() {
    eventDispatcher.set(testDispatcher, eventDispatcher.getPluginName());
  }

  @Test
  public void shouldEmitProjectDeletionEventsForOneRemote() throws Exception {
    String projectNameDeleted = "project-deleted";
    Project.NameKey replicaProject = createTestProject(projectNameDeleted + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    setProjectDeletionReplication("foo", true);
    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectNameDeleted));
    }

    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationScheduledEvent.class)).hasSize(1);

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));

    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationRunningEvent.class)).hasSize(1);
    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationSucceededEvent.class)).hasSize(1);
    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationDoneEvent.class)).hasSize(1);
  }

  @Test
  public void shouldEmitProjectDeletionEventsForMultipleRemotes() throws Exception {
    String projectNameDeleted = "project-deleted";
    Project.NameKey replicaProject1 = createTestProject(projectNameDeleted + "replica1");
    Project.NameKey replicaProject2 = createTestProject(projectNameDeleted + "replica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setProjectDeletionReplication("foo1", true);

    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    setProjectDeletionReplication("foo2", true);

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectNameDeleted));
    }

    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationScheduledEvent.class)).hasSize(2);

    waitUntil(() -> !nonEmptyProjectExists(replicaProject1) && !nonEmptyProjectExists(replicaProject2));

    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationRunningEvent.class)).hasSize(2);
    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationSucceededEvent.class)).hasSize(2);
    assertThat(testDispatcher.getMatching(projectNameDeleted, ProjectDeletionReplicationDoneEvent.class)).hasSize(1);
  }
}
