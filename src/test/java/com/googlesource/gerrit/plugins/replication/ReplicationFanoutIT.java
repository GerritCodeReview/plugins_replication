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
import static java.util.stream.Collectors.toList;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.After;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationFanoutIT extends ReplicationDaemon {
  private ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
    setReplicationDestinationRemoteConfig("remote1", "suffix1", Optional.of("not-used-project"));

    super.setUpTestPlugin();
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
  }

  @After
  public void cleanUp() throws IOException {
    if (Files.exists(sitePaths.etc_dir.resolve("replication"))) {
      MoreFiles.deleteRecursively(
          sitePaths.etc_dir.resolve("replication"), RecursiveDeleteOption.ALLOW_INSECURE);
    }
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    setReplicationDestinationRemoteConfig("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    isPushCompleted(targetProject, newBranch, TEST_PUSH_TIMEOUT);
    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void shouldReplicateNewBranchStorage() throws Exception {
    setReplicationDestinationRemoteConfig("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listWaitingTasks("refs/heads/(mybranch|master)")).hasSize(2);
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createTestProject(project + "replica1");
    Project.NameKey targetProject2 = createTestProject(project + "replica2");

    setReplicationDestinationRemoteConfig("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestinationRemoteConfig("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo1 = repoManager.openRepository(targetProject1);
        Repository repo2 = repoManager.openRepository(targetProject2)) {
      WaitUtil.waitUntil(
          () ->
              (checkedGetRef(repo1, sourceRef) != null && checkedGetRef(repo2, sourceRef) != null),
          TEST_PUSH_TIMEOUT);

      Ref targetBranchRef1 = getRef(repo1, sourceRef);
      assertThat(targetBranchRef1).isNotNull();
      assertThat(targetBranchRef1.getObjectId()).isEqualTo(sourceCommit.getId());

      Ref targetBranchRef2 = getRef(repo2, sourceRef);
      assertThat(targetBranchRef2).isNotNull();
      assertThat(targetBranchRef2.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotesStorage() throws Exception {
    createTestProject(project + "replica1");
    createTestProject(project + "replica2");

    setReplicationDestinationRemoteConfig("foo1", "replica1", ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestinationRemoteConfig("foo2", "replica2", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createChange();

    assertThat(listWaitingTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(2);
  }

  @Test
  public void shouldCreateIndividualReplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");

    setReplicationDestinationRemoteConfig("foo1", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestinationRemoteConfig("foo2", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createChange();

    assertThat(listWaitingTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);
  }

  private void setReplicationDestinationRemoteConfig(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestinationRemoteConfig(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_DELAY_SECONDS);
  }

  private void setReplicationDestinationRemoteConfig(
      String remoteName, String replicaSuffix, Optional<String> project, int replicationDelay)
      throws IOException {
    setReplicationDestinationRemoteConfig(
        remoteName, Arrays.asList(replicaSuffix), project, replicationDelay);
  }

  private void setReplicationDestinationRemoteConfig(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> allProjects,
      int replicationDelay)
      throws IOException {
    FileBasedConfig remoteConfig =
        new FileBasedConfig(
            sitePaths.etc_dir.resolve("replication/" + remoteName + ".config").toFile(),
            FS.DETECTED);

    setReplicationDestination(remoteConfig, replicaSuffixes, allProjects, replicationDelay);
  }

  private List<ReplicateRefUpdate> listWaitingTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage
        .streamWaiting()
        .filter(task -> task.refs().stream().anyMatch(ref -> refmaskPattern.matcher(ref).matches()))
        .collect(toList());
  }
}
