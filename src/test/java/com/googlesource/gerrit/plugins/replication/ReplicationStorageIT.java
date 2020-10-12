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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
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
public class ReplicationStorageIT extends LightweightPluginDaemonTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Optional<String> ALL_PROJECTS = Optional.empty();

  private static final int TEST_REPLICATION_DELAY_SECONDS = 1;
  private static final int TEST_REPLICATION_RETRY_MINUTES = 1;
  private static final int TEST_PUSH_TIME_SECONDS = 1;
  private static final Duration TEST_PUSH_TIMEOUT =
      Duration.ofSeconds(TEST_REPLICATION_DELAY_SECONDS + TEST_PUSH_TIME_SECONDS);

  @Inject private SitePaths sitePaths;
  private Path gitPath;
  private FileBasedConfig config;
  private ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");
    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.save();
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
    createTestProject("projectreplica1");
    createTestProject("projectreplica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createChange();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);
  }

  @Test
  public void shouldCreateOneReplicationTaskWhenSchedulingRepoFullSync() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, null, new ReplicationState(NO_OP), false);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
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

    String changeRef = createChange().getPatchSet().getRefName();
    changeReplicationTasksForRemote(changeRef, remote1).forEach(tasksStorage::delete);
    reloadConfig();

    assertThat(changeReplicationTasksForRemote(changeRef, remote2).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef, remote1).count()).isEqualTo(0);
  }

  @Test
  public void shouldFireAndCompletePendingOnlyToIncompleteUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject("project" + suffix1);
    Project.NameKey target2 = createTestProject("project" + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef = createChange().getPatchSet().getRefName();
    changeReplicationTasksForRemote(changeRef, remote1).forEach(tasksStorage::delete);

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

    String changeRef1 = createChange().getPatchSet().getRefName();
    String changeRef2 = createChange().getPatchSet().getRefName();
    reloadConfig();

    assertThat(changeReplicationTasksForRemote(changeRef1, remote1).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef1, remote2).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef2, remote1).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef2, remote2).count()).isEqualTo(1);
  }

  @Test
  public void shouldFireAndCompletePendingChangeRefs() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject("project" + suffix1);
    Project.NameKey target2 = createTestProject("project" + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef1 = createChange().getPatchSet().getRefName();
    String changeRef2 = createChange().getPatchSet().getRefName();

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
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();
    String expectedURI = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(tasksStorage.list()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
  }

  @Test
  public void shouldMatchRealUrl() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();
    String expectedURI = urlMatch;

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(tasksStorage.list()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_RETRY_MINUTES);
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, int replicationDelay)
      throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project, replicationDelay);
  }

  private FileBasedConfig setReplicationDestination(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay)
      throws IOException {
    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setInt("remote", remoteName, "replicationDelay", replicationDelay);
    config.setInt("remote", remoteName, "replicationRetry", TEST_REPLICATION_RETRY_MINUTES);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.save();
    return config;
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return createProject(name);
  }

  public boolean isPushCompleted(Project.NameKey project, String ref, Duration timeOut) {
    try (Repository repo = repoManager.openRepository(project)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, ref) != null, timeOut);
      return true;
    } catch (InterruptedException e) {
      return false;
    } catch (Exception e) {
      throw new RuntimeException("Cannot open repo for project" + project, e);
    }
  }

  private Stream<ReplicateRefUpdate> changeReplicationTasksForRemote(
      String changeRef, String remote) {
    return tasksStorage.list().stream()
        .filter(task -> changeRef.equals(task.ref))
        .filter(task -> remote.equals(task.remote));
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }

  private List<ReplicateRefUpdate> listReplicationTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage.list().stream()
        .filter(task -> refmaskPattern.matcher(task.ref).matches())
        .collect(toList());
  }
}
