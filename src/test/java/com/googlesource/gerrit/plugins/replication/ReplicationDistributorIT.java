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
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.git.WorkQueue;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/**
 * The tests in this class ensure the correctness of {@link
 * com.googlesource.gerrit.plugins.replication.ReplicationQueue.Distributor}
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
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
        getRefUpdateForBranch(remote, replica, master, newBranch);
    setReplicationDestination(remote, replica, ALL_PROJECTS);
    reloadConfig();

    List<WorkQueue.Task<?>> tasks = getProjectTasks();

    tasksStorage.create(ref); // Mimics RefUpdate inserted into storage by other Primary
    WaitUtil.waitUntil(
        () -> getProjectTasks().size() != 0, Duration.ofSeconds(TEST_DISTRIBUTION_CYCLE_SECONDS));

    tasks = getProjectTasks();
    assertThat(tasks).hasSize(1);
    assertTrue(
        tasks
            .get(0)
            .toString()
            .contains(ref.ref())); // ReplicationTask for the created ref in queue
    WaitUtil.waitUntil(
        () -> getProjectTasks().size() == 0, TEST_PUSH_TIMEOUT); // replication work completed

    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      Ref masterRef = getRef(sourceRepo, "refs/heads/master");
      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  private List<WorkQueue.Task<?>> getProjectTasks() {
    return getInstance(WorkQueue.class).getTasks().stream()
        .filter(t -> t instanceof WorkQueue.ProjectTask)
        .collect(Collectors.toList());
  }

  /*
   * This method creates the specified branch on source project and returns
   * the ReplicationRefUpdate obtained for that branch creation from storage
   * but doesn't do any actual replication work.
   */
  private ReplicationTasksStorage.ReplicateRefUpdate getRefUpdateForBranch(
      String remote, String replica, String fromRef, String refToCreate)
      throws IOException, RestApiException, URISyntaxException {
    setReplicationDestination(remote, replica, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    BranchInput input = new BranchInput();
    input.revision = fromRef;
    gApi.projects().name(project.get()).branch(refToCreate).create(input);

    List<ReplicationTasksStorage.ReplicateRefUpdate> waitingTask = listWaiting();
    ReplicationTasksStorage.ReplicateRefUpdate refUpdate = waitingTask.get(0);

    UriUpdates uriUpdates = TestUriUpdates.create(refUpdate);
    tasksStorage.start(uriUpdates);
    tasksStorage.finish(
        uriUpdates); // Doesn't do any actual replication work but removes the created ref

    return refUpdate;
  }
}
