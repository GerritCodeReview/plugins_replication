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
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ReplicationScheduledEvent;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UseLocalDisk
@Sandboxed
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationEventsIT extends ReplicationDaemon {
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

    assertThat(testDispatcher.getMatching(changeBranch, ReplicationScheduledEvent.class))
        .hasSize(1);
    assertThat(testDispatcher.getMatching(metaBranch, ReplicationScheduledEvent.class)).hasSize(1);

    isPushCompleted(targetProject, sourceRef, TEST_PUSH_TIMEOUT);
    isPushCompleted(targetProject, metaRef, TEST_PUSH_TIMEOUT);

    assertThat(testDispatcher.getMatching(RefReplicatedEvent.class))
        .hasSize(2); // FIXME: confirm this is 1 change ref and 1 notedb ref
    assertThat(testDispatcher.getMatching(RefReplicationDoneEvent.class))
        .hasSize(2); // FIXME: confirm this is 1 change ref and 1 notedb ref
  }

  @Test
  public void replicateNewBranchSendsEvents() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(
            testDispatcher.getMatching(
                BranchNameKey.create(project, newBranch), ReplicationScheduledEvent.class))
        .hasSize(1);

    isPushCompleted(targetProject, newBranch, TEST_PUSH_TIMEOUT);

    assertThat(testDispatcher.getMatching(RefReplicatedEvent.class))
        .hasSize(1); // FIXME: confirm this is the ref we expect
    assertThat(testDispatcher.getMatching(RefReplicationDoneEvent.class))
        .hasSize(1); // FIXME: confirm this is the ref we expect
  }
}
