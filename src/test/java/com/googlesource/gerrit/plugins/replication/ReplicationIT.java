// Copyright (C) 2019 The Android Open Source Project
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

import com.google.common.base.Stopwatch;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 5;
  private static final Duration TEST_TIMEMOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 10);

  @Inject private SitePaths sitePaths;
  private Path pluginDataDir;
  private Path gitPath;
  private Path storagePath;
  private FileBasedConfig config;
  private Gson GSON = new Gson();

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    config.save();

    super.setUpTestPlugin();

    pluginDataDir = plugin.getSysInjector().getInstance(Key.get(Path.class, PluginData.class));
    storagePath = pluginDataDir.resolve("ref-updates");
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    Project.NameKey sourceProject = createProject("foo");

    assertThat(listReplicationTasks("refs/meta/config")).hasSize(1);

    waitUntil(() -> gitPath.resolve(sourceProject + "replica.git").toFile().isDirectory());

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    Project.NameKey targetProject = createProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(1);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    Project.NameKey targetProject = createProject("projectreplica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listReplicationTasks("refs/heads/(mybranch|master)")).hasSize(2);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      Ref masterRef = getRef(repo, master);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createProject("projectreplica1");
    Project.NameKey targetProject2 = createProject("projectreplica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(2);

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
    createProject("projectreplica1");
    createProject("projectreplica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    createChange();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);
  }

  @Test
  public void shouldReplicateHeadUpdate() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createProject("projectreplica");
    String newHead = "refs/heads/newhead";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newHead).create(input);
    gApi.projects().name(project.get()).head(newHead);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newHead) != null);

      Ref targetProjectHead = getRef(repo, Constants.HEAD);
      assertThat(targetProjectHead).isNotNull();
      assertThat(targetProjectHead.getTarget().getName()).isEqualTo(newHead);
    }
  }

  @Test
  public void shouldReplicateBranchDeletion() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();
    waitForEmptyTasks();

    Project.NameKey targetProject = createProject("projectreplica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listReplicationTasks("refs/heads/(mybranch|master)")).hasSize(2);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);
    }

    gApi.projects().name(project.get()).branch(newBranch).delete();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) == null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNull();
    }
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

  private void setReplicationDestination(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    while (!waitCondition.get() && stopwatch.elapsed().compareTo(TEST_TIMEMOUT) < 0) {
      TimeUnit.SECONDS.sleep(1);
    }
  }

  private void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }

  private void waitForEmptyTasks() throws InterruptedException {
    waitUntil(
        () -> {
          try {
            return listReplicationTasks(".*").size() == 0;
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Failed to list replication tasks");
            throw new IllegalStateException(e);
          }
        });
  }

  private List<ReplicateRefUpdate> listReplicationTasks(String refRegex) throws IOException {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    List<ReplicateRefUpdate> tasks = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(storagePath)) {
      for (Path path : files) {
        ReplicateRefUpdate task = readTask(path);
        if (refmaskPattern.matcher(task.ref).matches()) {
          tasks.add(readTask(path));
        }
      }
    }

    return tasks;
  }

  private ReplicateRefUpdate readTask(Path file) {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return GSON.fromJson(reader, ReplicateRefUpdate.class);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to read replication task %s", file);
      throw new IllegalStateException(e);
    }
  }
}
