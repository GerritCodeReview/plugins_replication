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

import com.google.common.flogger.FluentLogger;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationFanoutIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  private Path pluginDataDir;
  private Path gitPath;
  private Path storagePath;
  private FileBasedConfig config;
  private ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    setAutoReload();
    config.save();

    setReplicationDestination("remote1", "suffix1", Optional.of("not-used-project"));

    super.setUpTestPlugin();

    pluginDataDir = plugin.getSysInjector().getInstance(Key.get(Path.class, PluginData.class));
    storagePath = pluginDataDir.resolve("ref-updates");
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
    cleanupReplicationTasks();
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
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listIncompleteTasks("refs/heads/(mybranch|master)")).hasSize(2);

    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createTestProject(project + "replica1");
    Project.NameKey targetProject2 = createTestProject(project + "replica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    assertThat(listIncompleteTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(2);

    try (Repository repo1 = repoManager.openRepository(targetProject1);
        Repository repo2 = repoManager.openRepository(targetProject2)) {
      waitUntil(
          () ->
              (checkedGetRef(repo1, sourceRef) != null && checkedGetRef(repo2, sourceRef) != null));

      Ref targetBranchRef1 = getRef(repo1, sourceRef);
      assertThat(targetBranchRef1).isNotNull();
      assertThat(targetBranchRef1.getObjectId()).isEqualTo(sourceCommit.getId());

      Ref targetBranchRef2 = getRef(repo2, sourceRef);
      assertThat(targetBranchRef2).isNotNull();
      assertThat(targetBranchRef2.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldCreateIndividualReplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");

    FileBasedConfig dest1 = setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    FileBasedConfig dest2 = setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
    dest1.setInt("remote", null, "replicationDelay", TEST_REPLICATION_DELAY * 100);
    dest2.setInt("remote", null, "replicationDelay", TEST_REPLICATION_DELAY * 100);
    dest1.save();
    dest2.save();
    reloadConfig();

    createChange();

    assertThat(listIncompleteTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
  }

  private Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project);
  }

  private FileBasedConfig setReplicationDestination(
      String remoteName, List<String> replicaSuffixes, Optional<String> allProjects)
      throws IOException {
    FileBasedConfig remoteConfig =
        new FileBasedConfig(
            sitePaths.etc_dir.resolve("replication/" + remoteName + ".config").toFile(),
            FS.DETECTED);

    setReplicationDestination(remoteConfig, replicaSuffixes, allProjects);
    return remoteConfig;
  }

  private void setAutoReload() throws IOException {
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  private void setReplicationDestination(
      FileBasedConfig config, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", null, "url", replicaUrls);
    config.setInt("remote", null, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> config.setString("remote", null, "projects", prj));

    config.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private void reloadConfig() {
    getAutoReloadConfigDecoratorInstance().reload();
  }

  private AutoReloadConfigDecorator getAutoReloadConfigDecoratorInstance() {
    return getInstance(AutoReloadConfigDecorator.class);
  }

  private <T> T getInstance(Class<T> classObj) {
    return plugin.getSysInjector().getInstance(classObj);
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  @SuppressWarnings(
      "SynchronizeOnNonFinalField") // tasksStorage is non-final but only set in setUpTestPlugin()
  private List<ReplicateRefUpdate> listIncompleteTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    synchronized (tasksStorage) {
      return Stream.concat(tasksStorage.listWaiting().stream(), tasksStorage.listRunning().stream())
          .filter(task -> refmaskPattern.matcher(task.ref).matches())
          .collect(toList());
    }
  }

  public void cleanupReplicationTasks() throws IOException {
    cleanupReplicationTasks(storagePath);
  }

  private void cleanupReplicationTasks(Path basePath) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(basePath)) {
      for (Path path : files) {
        if (Files.isDirectory(path)) {
          cleanupReplicationTasks(path);
        } else {
          path.toFile().delete();
        }
      }
    }
  }
}
