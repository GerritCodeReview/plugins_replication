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
import static java.util.stream.Collectors.toList;

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
public class ReplicationStorageIT extends ReplicationDaemon {
  private static final int TEST_TASK_FINISH_SECONDS = 1;
  protected static final Duration TEST_TASK_FINISH_TIMEOUT =
      Duration.ofSeconds(TEST_TASK_FINISH_SECONDS);
  protected ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
  }

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
    changeReplicationTasksForRemote(tasksStorage.listWaiting().stream(), changeRef, remote1)
        .forEach(
            (update) -> {
              try {
                UriUpdates uriUpdates = TestUriUpdates.create(update);
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
    changeReplicationTasksForRemote(tasksStorage.listWaiting().stream(), changeRef, remote1)
        .forEach(
            (update) -> {
              try {
                UriUpdates uriUpdates = TestUriUpdates.create(update);
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

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    assertThat(isPushCompleted(target1, changeRef1, TEST_PUSH_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target2, changeRef1, TEST_PUSH_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target1, changeRef2, TEST_PUSH_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target2, changeRef2, TEST_PUSH_TIMEOUT)).isEqualTo(true);
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

    assertThat(tasksStorage.listWaiting()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.listWaiting()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
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

    assertThat(tasksStorage.listWaiting()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.listWaiting()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
  }

  @Test
  public void shouldReplicateBranchDeletionWhenMirror() throws Exception {
    replicateBranchDeletion(true);
  }

  @Test
  public void shouldNotReplicateBranchDeletionWhenNotMirror() throws Exception {
    replicateBranchDeletion(false);
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

  @Test
  public void shouldCleanupTasksAfterNewProjectReplication() throws Exception {
    setReplicationDestination("task_cleanup_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(tasksStorage.listRunning()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_project");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);
    WaitUtil.waitUntil(() -> tasksStorage.listRunning().size() == 0, TEST_TASK_FINISH_TIMEOUT);
  }

  private Stream<ReplicateRefUpdate> waitingChangeReplicationTasksForRemote(
      String changeRef, String remote) {
    return tasksStorage.listWaiting().stream()
        .filter(task -> changeRef.equals(task.ref))
        .filter(task -> remote.equals(task.remote));
  }

  private Stream<ReplicateRefUpdate> changeReplicationTasksForRemote(
      Stream<ReplicateRefUpdate> updates, String changeRef, String remote) {
    return updates
        .filter(task -> changeRef.equals(task.ref))
        .filter(task -> remote.equals(task.remote));
  }

  private List<ReplicateRefUpdate> listWaitingReplicationTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage.listWaiting().stream()
        .filter(task -> refmaskPattern.matcher(task.ref).matches())
        .collect(toList());
  }
}
