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
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.NO_OP;

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.googlesource.gerrit.plugins.replication.Destination.QueueInfo;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

/**
 * The tests in this class aim to ensure events are correctly written and read from storage. They
 * typically do this by setting up replication destinations with long delays, performing actions
 * that are expected to write to storage, reloading the configuration (which should read from
 * storage), and then confirming the actions complete as expected.
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationStorageIT extends ReplicationStorageDaemon {

  @Test
  public void shouldCreateIndividualReplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");
    createTestProject(project + "replica1");
    createTestProject(project + "replica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createChange();

    assertThat(listWaitingReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);
  }

  @Test
  public void shouldCreateOneReplicationTaskWhenSchedulingRepoFullSync() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, null, new ReplicationState(NO_OP), false);

    assertThat(listWaitingReplicationTasks(Pattern.quote(PushOne.ALL_REFS))).hasSize(1);
  }

  @Test
  public void shouldFirePendingOnlyToIncompleteUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef = createChange().getPatchSet().refName();
    changeReplicationTasksForRemote(tasksStorage.streamWaiting(), changeRef, remote1)
        .forEach(
            (update) -> {
              try {
                UriUpdates uriUpdates = new TestUriUpdates(update);
                tasksStorage.start(uriUpdates);
                tasksStorage.finish(uriUpdates);
              } catch (URISyntaxException e) {
              }
            });
    reloadConfig();

    assertThat(waitingChangeReplicationTasksForRemote(changeRef, remote2).count()).isEqualTo(1);
    assertThat(waitingChangeReplicationTasksForRemote(changeRef, remote1).count()).isEqualTo(0);
  }

  @Test
  public void shouldFireAndCompletePendingOnlyToIncompleteUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject(project + suffix1);
    Project.NameKey target2 = createTestProject(project + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef = createChange().getPatchSet().refName();
    changeReplicationTasksForRemote(tasksStorage.streamWaiting(), changeRef, remote1)
        .forEach(
            (update) -> {
              try {
                UriUpdates uriUpdates = new TestUriUpdates(update);
                tasksStorage.start(uriUpdates);
                tasksStorage.finish(uriUpdates);
              } catch (URISyntaxException e) {
              }
            });

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    assertThat(isPushCompleted(target2, changeRef, TEST_PUSH_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target1, changeRef, TEST_PUSH_TIMEOUT)).isEqualTo(false);
  }

  @Test
  public void shouldFirePendingChangeRefs() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef1 = createChange().getPatchSet().refName();
    String changeRef2 = createChange().getPatchSet().refName();
    reloadConfig();

    assertThat(waitingChangeReplicationTasksForRemote(changeRef1, remote1).count()).isEqualTo(1);
    assertThat(waitingChangeReplicationTasksForRemote(changeRef1, remote2).count()).isEqualTo(1);
    assertThat(waitingChangeReplicationTasksForRemote(changeRef2, remote1).count()).isEqualTo(1);
    assertThat(waitingChangeReplicationTasksForRemote(changeRef2, remote2).count()).isEqualTo(1);
  }

  @Test
  public void shouldFireAndCompletePendingChangeRefs() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject(project + suffix1);
    Project.NameKey target2 = createTestProject(project + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef1 = createChange().getPatchSet().refName();
    String changeRef2 = createChange().getPatchSet().refName();
    Map<Project.NameKey, String> refsByProject = new HashMap<>();
    refsByProject.put(target1, changeRef1);
    refsByProject.put(target2, changeRef1);
    refsByProject.put(target1, changeRef2);
    refsByProject.put(target2, changeRef2);

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    // Wait for completion within the time 2 pushes should take because each remote only has 1
    // thread and needs to push 2 events
    assertThat(isPushCompleted(refsByProject, TEST_PUSH_TIMEOUT.plus(TEST_PUSH_TIMEOUT)))
        .isEqualTo(true);
  }

  @Test
  public void shouldMatchTemplatedUrl() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();
    String expectedURI = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(listWaiting()).hasSize(1);
    tasksStorage
        .streamWaiting()
        .forEach(
            (task) -> {
              assertThat(task.uri()).isEqualTo(expectedURI);
              assertThat(task.refs()).isEqualTo(Set.of(PushOne.ALL_REFS));
            });
  }

  @Test
  public void shouldMatchRealUrl() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();
    String expectedURI = urlMatch;

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(listWaiting()).hasSize(1);
    tasksStorage
        .streamWaiting()
        .forEach(
            (task) -> {
              assertThat(task.uri()).isEqualTo(expectedURI);
              assertThat(task.refs()).isEqualTo(Set.of(PushOne.ALL_REFS));
            });
  }

  @Test
  public void shouldReplicateBranchDeletionWhenMirror() throws Exception {
    replicateBranchDeletion(true);
  }

  @Test
  public void shouldNotReplicateBranchDeletionWhenNotMirror() throws Exception {
    replicateBranchDeletion(false);
  }

  @Test
  public void shouldCleanupTasksAfterNewProjectReplication() throws Exception {
    setReplicationDestination("task_cleanup_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(listRunning()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_project");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);
    WaitUtil.waitUntil(() -> listRunning().size() == 0, TEST_TASK_FINISH_TIMEOUT);
  }

  @Test
  public void shouldCleanupBothTasksAndLocksAfterNewProjectReplication() throws Exception {
    setReplicationDestination("task_cleanup_locks_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_locks_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(listRunning()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_locks_project");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);
    WaitUtil.waitUntil(() -> isTaskCleanedUp(), TEST_TASK_FINISH_TIMEOUT);
  }

  @Test
  public void shouldCleanupBothTasksAndLocksAfterReplicationCancelledAfterMaxRetries()
      throws Exception {
    String projectName = "task_cleanup_locks_project_cancelled";
    String remoteDestination = "http://invalidurl:9090/";
    URIish urish = new URIish(remoteDestination + projectName + ".git");

    setReplicationDestination(projectName, "replica", Optional.of(projectName));
    // replace correct urls with invalid one to trigger retry
    config.setString("remote", projectName, "url", remoteDestination + "${name}.git");
    config.setInt("remote", projectName, "replicationMaxRetries", TEST_REPLICATION_MAX_RETRIES);
    config.save();
    reloadConfig();
    Destination destination =
        destinationCollection.getAll(FilterType.ALL).stream()
            .filter(dest -> dest.getProjects().contains(projectName))
            .findFirst()
            .get();

    createTestProject(projectName);

    WaitUtil.waitUntil(
        () -> isTaskRescheduled(destination.getQueueInfo(), urish), TEST_NEW_PROJECT_TIMEOUT);
    // replicationRetry is set to 1 minute which is the minimum value. That's why
    // should be safe to get the pushOne object from pending because it should be
    // here for one minute
    PushOne pushOp = destination.getQueueInfo().pending.get(urish);

    WaitUtil.waitUntil(() -> pushOp.wasCanceled(), MAX_RETRY_WITH_TOLERANCE_TIMEOUT);
    WaitUtil.waitUntil(() -> isTaskCleanedUp(), TEST_TASK_FINISH_TIMEOUT);
  }

  @Test
  public void shouldNotLeakTasksForSkippedConfigProjectReplication() throws Exception {
    String testRemote = "test_remote";
    setReplicationDestination(testRemote, "replica", ALL_PROJECTS);
    setPermissionsReplication(testRemote, false);
    reloadConfig();
    Project.NameKey permsProject = createTestPermissionsProject("test_permissions_only_project");
    Project.NameKey sourceProject = createTestProject("test_replication_source_project");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);
    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();

    // We now know that the regular project creation replicated properly.
    // Next we check that the permissions only project did not replicate
    // as we have disabled replication for permissions only projects.
    ProjectInfo replicaPermsProject = gApi.projects().name(permsProject + "replica").get();
    assertThat(replicaPermsProject).isNull();

    // Finally we check that both the waiting and running queues are empty
    // to ensure that permissions replication isn't somehow still in flight
    // which would represent a leaked task file on disk.
    assertThat(listWaiting()).hasSize(0);
    assertThat(listRunning()).hasSize(0);
  }

  @Test
  public void shouldNotLeakTasksForSkippedRefMatchReplication() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");
    String testRemote = "doNotLeakTasksForUnMatchedRefs";

    setReplicationDestination(testRemote, "replica", ALL_PROJECTS);
    // Only replicate refs/heads/* so that we know when replication has
    // completed after creating a new change while not replicating the change
    // ref. Master would be created for us when creating the repo so we
    // need a second branch to check.
    config.setString("remote", testRemote, "push", "+refs/heads/*:refs/heads/*");
    reloadConfig();

    // Create a new change first then create a new branch. This way we can
    // see that the change is not replicated when the branch is replicated.
    String changeRef = createChange().getPatchSet().refName();
    String newBranch = "refs/heads/newBranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, newBranch) != null);
      assertThat(checkedGetRef(repo, changeRef)).isNull();
    }

    // Finally ensure that there are new queued tasks to disks. We don't
    // want to leak them when work is complete.
    assertThat(listWaiting()).hasSize(0);
    assertThat(listRunning()).hasSize(0);
  }

  @Test
  public void shouldNotLeakTasksForSkippedRefPermsReplication() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");
    String testRemote = "doNotLeakTasksForNoPermsRefs";

    setReplicationDestination(testRemote, "replica", ALL_PROJECTS);
    // Set authGroup to Anonymous Users so that when we update refs/meta/config
    // with replicatePermissions set to True (default) we will filter on user
    // access rules not replicatePermissions settings.
    config.setString("remote", testRemote, "authGroup", "Anonymous Users");
    reloadConfig();

    // TODO: Update refs/meta/config then create a new branch. The new branch
    // should replicate but updated refs/meta/config will not.

    String changeRef = createChange().getPatchSet().refName();
    String newBranch = "refs/heads/newBranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, newBranch) != null);
      Ref targetConfigHead = checkedGetRef(repo, "refs/meta/config");
    }
    try (Repository repo = repoManager.openRepository(project)) {
      Ref sourceConfigHead = checkedGetRef(repo, "refs/meta/config");
    }
    assertThat(sourceConfigHead).isNotNull();
    assertThat(targetConfigHead).isNotNull();
    assertThat(sourceConfigHead.getObjectId()).isNotEqualTo(targetConfigHead.getObjectId());

    // Finally ensure that there are new queued tasks to disks. We don't
    // want to leak them when work is complete.
    assertThat(listWaiting()).hasSize(0);
    assertThat(listRunning()).hasSize(0);
  }

  private void replicateBranchDeletion(boolean mirror) throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String branchToDelete = "refs/heads/todelete";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(branchToDelete).create(input);
    isPushCompleted(targetProject, branchToDelete, TEST_PUSH_TIMEOUT);

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE, mirror);
    reloadConfig();

    gApi.projects().name(project.get()).branch(branchToDelete).delete();

    assertThat(listWaitingReplicationTasks(branchToDelete)).hasSize(1);
  }

  private boolean isTaskRescheduled(QueueInfo queue, URIish uri) {
    PushOne pushOne = queue.pending.get(uri);
    return pushOne == null ? false : pushOne.isRetrying();
  }

  private boolean isTaskCleanedUp() {
    Path refUpdates = replicationConfig.getEventsDirectory().resolve("ref-updates");
    Path runningUpdates = refUpdates.resolve("running");
    try {
      return Files.list(runningUpdates).count() == 0;
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  private Stream<ReplicateRefUpdate> waitingChangeReplicationTasksForRemote(
      String changeRef, String remote) {
    return tasksStorage
        .streamWaiting()
        .filter(task -> task.refs().stream().anyMatch(ref -> changeRef.equals(ref)))
        .filter(task -> remote.equals(task.remote()));
  }

  private Stream<ReplicateRefUpdate> changeReplicationTasksForRemote(
      Stream<ReplicateRefUpdate> updates, String changeRef, String remote) {
    return updates
        .filter(task -> task.refs().stream().anyMatch(ref -> changeRef.equals(ref)))
        .filter(task -> remote.equals(task.remote()));
  }
}
