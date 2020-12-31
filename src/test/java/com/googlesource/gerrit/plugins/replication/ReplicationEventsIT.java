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

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UseLocalDisk
@Sandboxed
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationEventsIT extends ReplicationDaemon {

  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  @Inject private DynamicItem<EventDispatcher> eventDispatcher;
  private TestDispatcher testDispatcher;

  @Before
  public void setup() throws Exception {
    initConfig();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
    testDispatcher = new TestDispatcher();
    eventDispatcher.set(testDispatcher, eventDispatcher.getPluginName());
  }

  @Test
  public void shouldEmitProjectDeletionEventsForOneRemote() throws Exception {
    String projectName = project.get();
    Project.NameKey replicaProject = setReplicationTarget("replica", project.get());

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    assertThat(
            testDispatcher.getMatching(projectName, ProjectDeletionReplicationScheduledEvent.class))
        .hasSize(1);

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));

    assertThat(
            testDispatcher.getMatching(projectName, ProjectDeletionReplicationSucceededEvent.class))
        .hasSize(1);
    assertThat(testDispatcher.getMatching(projectName, ProjectDeletionReplicationDoneEvent.class))
        .hasSize(1);
  }

  @Test
  public void shouldEmitProjectDeletionEventsForMultipleRemotes() throws Exception {
    String projectName = project.get();
    Project.NameKey replica1Project = setReplicationTarget("replica1", projectName);
    Project.NameKey replica2Project = setReplicationTarget("replica2", projectName);

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    assertThat(
            testDispatcher.getMatching(projectName, ProjectDeletionReplicationScheduledEvent.class))
        .hasSize(2);

    waitUntil(
        () -> !nonEmptyProjectExists(replica1Project) && !nonEmptyProjectExists(replica2Project));

    assertThat(
            testDispatcher.getMatching(projectName, ProjectDeletionReplicationSucceededEvent.class))
        .hasSize(2);
    assertThat(testDispatcher.getMatching(projectName, ProjectDeletionReplicationDoneEvent.class))
        .hasSize(1);
  }

  private Project.NameKey setReplicationTarget(String replica, String ofProject) throws Exception {
    Project.NameKey replicaProject = createTestProject(String.format("%s%s", ofProject, replica));
    setReplicationDestination(replica, replica, Optional.of(ofProject));
    setProjectDeletionReplication(replica, true);
    return replicaProject;
  }
}
