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

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

/**
 * The tests in this class ensure the correctness of {@link
 * com.googlesource.gerrit.plugins.replication.ReplicationQueue.Distributor}
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationDistributorIT extends ReplicationStorageDaemon {
  private static final int TEST_DISTRIBUTION_INTERVAL_SECONDS = 3;
  private static final int TEST_DISTRIBUTION_DURATION_SECONDS = 1;
  private static final int TEST_DISTRIBUTION_CYCLE_SECONDS =
      TEST_DISTRIBUTION_INTERVAL_SECONDS + TEST_DISTRIBUTION_DURATION_SECONDS;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setDistributionInterval(TEST_DISTRIBUTION_INTERVAL_SECONDS);
    super.setUpTestPlugin();
  }

  @Test
  public void distributorAddingTaskFromStorage() throws Exception {
    String remote = "foo";
    String replica = "replica";
    String master = "refs/heads/master";
    String newBranch = "refs/heads/foo_branch";
    Project.NameKey targetProject = createTestProject(project + replica);
    ReplicationTasksStorage.ReplicateRefUpdate ref =
        ReplicationTasksStorage.ReplicateRefUpdate.create(
            project.get(), Set.of(newBranch), new URIish(getProjectUri(targetProject)), remote);
    createBranch(project, master, newBranch);
    setReplicationDestination(remote, replica, ALL_PROJECTS);
    reloadConfig();

    tasksStorage.create(ref); // Mimics RefUpdate inserted into storage by other Primary
    WaitUtil.waitUntil(
        () -> getProjectTasks().size() != 0, Duration.ofSeconds(TEST_DISTRIBUTION_CYCLE_SECONDS));

    List<WorkQueue.Task<?>> tasks = getProjectTasks();
    assertThat(tasks).hasSize(1); // ReplicationTask for the created ref in queue
    assertThat(waitForProjectTaskCount(0, TEST_PUSH_TIMEOUT)).isTrue();

    try (Repository targetRepo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRef = getRef(targetRepo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void distributorPrunesTaskFromWorkQueue() throws Exception {
    createTestProject(project + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String newBranch = "refs/heads/foo_branch";
    createBranch(BranchNameKey.create(project, newBranch));

    assertThat(listWaitingReplicationTasks(newBranch)).hasSize(1);
    deleteWaitingReplicationTasks(newBranch); // This simulates the work being started by other node

    assertThat(waitForProjectTaskCount(0, Duration.ofSeconds(TEST_DISTRIBUTION_CYCLE_SECONDS)))
        .isTrue();
  }

  private List<WorkQueue.Task<?>> getProjectTasks() {
    return getInstance(WorkQueue.class).getTasks().stream()
        .filter(t -> t instanceof WorkQueue.ProjectTask)
        .collect(Collectors.toList());
  }

  private void createBranch(Project.NameKey project, String fromRef, String refToCreate)
      throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      Ref from = repo.exactRef(fromRef);
      RefUpdate createBranch = repo.updateRef(refToCreate);
      createBranch.setNewObjectId(from.getObjectId());
      createBranch.update();
    }
  }

  private boolean waitForProjectTaskCount(int count, Duration duration) {
    try {
      WaitUtil.waitUntil(() -> getProjectTasks().size() == count, duration);
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }
}
