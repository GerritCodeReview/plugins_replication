// Copyright (C) 2020 The Android Open Source Project
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

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/**
 * The tests in this class ensure that events in the storage are correctly managed under multi-
 * primary scenarios.
 *
 * @see com.googlesource.gerrit.plugins.replication.ReplicationStorageIT
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationStorageMPIT extends ReplicationStorageDaemon {

  @Test
  public void workFromOnlyWaitingIsPerformed() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS, TEST_LONG_REPLICATION_DELAY_SECONDS);
    reloadConfig();

    String newBranchA = "refs/heads/foo_branch_a";
    String newBranchB = "refs/heads/foo_branch_b";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranchA).create(input);
    gApi.projects().name(project.get()).branch(newBranchB).create(input);

    deleteWaitingReplicationTasks(
        newBranchA); // This simulates the work being completed by other node
    assertThat(listWaitingReplicationTasks("refs/heads/foo_branch_.*")).hasSize(1);

    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      WaitUtil.waitUntil(
          () -> checkedGetRef(repo, newBranchA) == null && checkedGetRef(repo, newBranchB) != null,
          TEST_PUSH_TIMEOUT_LONG);

      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRefA = getRef(repo, newBranchA);
      Ref targetBranchRefB = getRef(repo, newBranchB);
      assertThat(targetBranchRefA).isNull();
      assertThat(targetBranchRefB).isNotNull();
      assertThat(targetBranchRefB.getObjectId()).isEqualTo(masterRef.getObjectId());
    }

    WaitUtil.waitUntil(() -> listRunning().isEmpty(), TEST_TASK_FINISH_TIMEOUT);
    assertThat(listWaiting()).isEmpty();
  }
}
