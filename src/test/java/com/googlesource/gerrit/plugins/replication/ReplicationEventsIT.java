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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.RefEvent;
import com.google.inject.Inject;
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

  private static final Duration TEST_SCHEDULE_EVENT_TIMEOUT =
      Duration.ofSeconds(TEST_REPLICATION_DELAY_SECONDS);

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
  public void replicateNewChangeSendsEvents() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().refName();
    String metaRef = pushResult.getChange().notes().getRefName();
    BranchNameKey changeBranch = BranchNameKey.create(project, sourceRef);
    BranchNameKey metaBranch = BranchNameKey.create(project, metaRef);

    waitForRefEvent(
        () -> testDispatcher.getMatching(changeBranch, ReplicationScheduledEvent.class), sourceRef);
    waitForRefEvent(
        () -> testDispatcher.getMatching(metaBranch, ReplicationScheduledEvent.class), metaRef);

    isPushCompleted(targetProject, sourceRef, TEST_PUSH_TIMEOUT);
    isPushCompleted(targetProject, metaRef, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicatedEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicatedEvent.class), sourceRef);
    assertThat(testDispatcher.getMatching(RefReplicatedEvent.class).size()).isEqualTo(2);

    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicationDoneEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicationDoneEvent.class), sourceRef);
    assertThat(testDispatcher.getMatching(RefReplicatedEvent.class).size()).isEqualTo(2);
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

    waitForRefEvent(
        () -> testDispatcher.getMatching(branchName, ReplicationScheduledEvent.class), newBranch);

    isPushCompleted(targetProject, newBranch, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicatedEvent.class), newBranch);
    assertThat(testDispatcher.getMatching(RefReplicatedEvent.class).size()).isEqualTo(1);

    waitForRefEvent(() -> testDispatcher.getMatching(RefReplicationDoneEvent.class), newBranch);
    assertThat(testDispatcher.getMatching(RefReplicationDoneEvent.class).size()).isEqualTo(1);
  }

  private <T extends RefEvent> void waitForRefEvent(Supplier<List<T>> events, String refName)
      throws InterruptedException {
    WaitUtil.waitUntil(
        () -> events.get().stream().filter(e -> refName.equals(e.getRefName())).count() == 1,
        TEST_SCHEDULE_EVENT_TIMEOUT);
  }
}
