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
import static org.junit.Assert.assertTrue;

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.server.git.WorkQueue;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationDistributorIT extends ReplicationStorageDaemon {
  private static final int TEST_DISTRIBUTION_INTERVAL = 3;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setDistributionInterval(TEST_DISTRIBUTION_INTERVAL);
    super.setUpTestPlugin();
  }

  @Test
  public void distributorAddingTaskFromStorage() throws Exception {
    createTestProject(project + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS, TEST_LONG_REPLICATION_DELAY_SECONDS);
    reloadConfig();

    String newBranch = "refs/heads/foo_branch";
    BranchInput input = new BranchInput();
    input.revision = "refs/heads/master";
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    List<ReplicationTasksStorage.ReplicateRefUpdate> waitingTask = listWaiting();
    assertThat(waitingTask).hasSize(1);
    ReplicationTasksStorage.ReplicateRefUpdate ref = waitingTask.get(0);

    List<WorkQueue.Task<?>> tasks = getProjectTasks();
    assertThat(tasks).hasSize(1);
    assertTrue(tasks.get(0).toString().contains(newBranch));

    WaitUtil.waitUntil(
        () -> getProjectTasks().isEmpty(), TEST_PUSH_TIMEOUT_LONG); // Implies the work is completed
    assertThat(getProjectTasks()).hasSize(0);

    tasksStorage.create(ref); // Puts the ref obtained above back into storage.
    WaitUtil.waitUntil(
        () -> getProjectTasks().size() != 0, Duration.ofSeconds(TEST_DISTRIBUTION_INTERVAL + 1));

    tasks = getProjectTasks();
    assertThat(tasks).hasSize(1);
    assertTrue(tasks.get(0).toString().contains(newBranch));
  }

  private List<WorkQueue.Task<?>> getProjectTasks() {
    return getInstance(WorkQueue.class).getTasks().stream()
        .filter(t -> t instanceof WorkQueue.ProjectTask)
        .collect(Collectors.toList());
  }
}
