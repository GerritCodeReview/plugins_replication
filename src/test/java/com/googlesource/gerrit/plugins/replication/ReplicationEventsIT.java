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

import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationScheduledEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationSucceededEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ReplicationScheduledEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;

@UseLocalDisk
@Sandboxed
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationEventsIT extends ReplicationDaemon {
  private static final Duration TEST_POST_EVENT_TIMEOUT = Duration.ofSeconds(1);

  protected static final int TEST_PROJECT_DELETION_TIME_SECONDS = 1;
  private static final Duration TEST_DELETE_PROJECT_TIMEOUT =
      Duration.ofSeconds(
          (TEST_REPLICATION_DELAY_SECONDS + TEST_REPLICATION_RETRY_MINUTES * 60)
              + TEST_PROJECT_DELETION_TIME_SECONDS);

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
    setUpTestPlugin();
    testDispatcher = new TestDispatcher();
    eventDispatcher.set(testDispatcher, eventDispatcher.getPluginName());
  }

  @Test
  public void replicateNewChangeSendsEvents() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().refName();
    String metaRef = pushResult.getChange().notes().getRefName();
    BranchNameKey changeBranch = BranchNameKey.create(project, sourceRef);
    BranchNameKey metaBranch = BranchNameKey.create(project, metaRef);

    assertThat(testDispatcher.getEvents(changeBranch, ReplicationScheduledEvent.class)).hasSize(1);
    assertThat(testDispatcher.getEvents(metaBranch, ReplicationScheduledEvent.class)).hasSize(1);

    isPushCompleted(targetProject, sourceRef, TEST_PUSH_TIMEOUT);
    isPushCompleted(targetProject, metaRef, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), sourceRef);
    assertThat(testDispatcher.getEvents(RefReplicatedEvent.class).size()).isEqualTo(2);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), sourceRef);
    assertThat(testDispatcher.getEvents(RefReplicationDoneEvent.class).size()).isEqualTo(2);
  }

  @Test
  public void replicateNewBranchSendsEvents() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    BranchNameKey branchName = BranchNameKey.create(project, newBranch);
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(testDispatcher.getEvents(branchName, ReplicationScheduledEvent.class)).hasSize(1);

    isPushCompleted(targetProject, newBranch, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), newBranch);
    assertThat(testDispatcher.getEvents(RefReplicatedEvent.class).size()).isEqualTo(1);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), newBranch);
    assertThat(testDispatcher.getEvents(RefReplicationDoneEvent.class).size()).isEqualTo(1);
  }

  @Test
  public void shouldEmitProjectDeletionEventsForOneRemote() throws Exception {
    String projectName = project.get();
    setReplicationTarget("replica", project.get());

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    assertThat(testDispatcher.getEvents(project, ProjectDeletionReplicationScheduledEvent.class))
        .hasSize(1);

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class), 1);
    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class), 1);
  }

  @Test
  public void shouldEmitProjectDeletionEventsForMultipleRemotes() throws Exception {
    String projectName = project.get();
    setReplicationTarget("replica1", projectName);
    setReplicationTarget("replica2", projectName);

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    assertThat(testDispatcher.getEvents(project, ProjectDeletionReplicationScheduledEvent.class))
        .hasSize(2);
    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class), 2);
    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class), 1);
  }

  private <T extends RefEvent> void waitForRefEvent(Supplier<List<T>> events, String refName)
      throws InterruptedException {
    WaitUtil.waitUntil(
        () -> events.get().stream().filter(e -> refName.equals(e.getRefName())).count() == 1,
        TEST_POST_EVENT_TIMEOUT);
  }

  private <T extends ProjectEvent> void waitForProjectEvent(Supplier<List<T>> events, int count)
      throws InterruptedException {
    WaitUtil.waitUntil(() -> events.get().size() == count, TEST_POST_EVENT_TIMEOUT);
  }

  private Project.NameKey setReplicationTarget(String replica, String ofProject) throws Exception {
    Project.NameKey replicaProject = createTestProject(String.format("%s%s", ofProject, replica));
    setReplicationDestination(replica, replica, Optional.of(ofProject));
    setProjectDeletionReplication(replica, true);
    return replicaProject;
  }
}
