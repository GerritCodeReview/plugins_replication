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
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.Task;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.UriLock;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationMultiMasterIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final int TEST_RESCHEDULE_DELAY = 3; // minimum and default
  private static final Duration TEST_PUSH_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);
  private static final Duration TEST_2PUSH_TIMEOUT = TEST_PUSH_TIMEOUT.multipliedBy(2);
  private static final Duration TEST_RESCHEDULE_TIMEOUT =
      Duration.ofSeconds(TEST_RESCHEDULE_DELAY * 2);

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
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    config.save();

    super.setUpTestPlugin();

    pluginDataDir = plugin.getSysInjector().getInstance(Key.get(Path.class, PluginData.class));
    storagePath = pluginDataDir.resolve("ref-updates");
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
    cleanupReplicationTasks();
  }

  @Test
  public void shouldRetryRunningExternally() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey replica1 = createTestProject(project + suffix1);
    Project.NameKey replica2 = createTestProject(project + suffix2);

    // By putting both replicas in the same remote with a single thread,
    // they will be serialized, thus replica1 will fire before replica2.
    setReplicationDestination("foo", Arrays.asList(suffix1, suffix2), ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().refName();

    // Simulate another update to same URI inFlight externally on another node
    String uri1 = gitPath.resolve(replica1.get() + ".git").toString();
    UriLock lock = getUriLock(uri1);
    lock.acquire();

    // By waiting for the second push to complete, we know we have
    // waited long enough that the first push should have completed if
    // it were not blocked.
    WaitUtil.waitUntil(() -> checkedGetRef(replica2, sourceRef) != null, TEST_2PUSH_TIMEOUT);

    // Ensure that the collision prevented the push
    assertThat(isPushCompleted(replica1, sourceRef, TEST_PUSH_TIMEOUT)).isEqualTo(false);

    lock.release(); // simulate other node completing

    assertThat(isPushCompleted(replica1, sourceRef, TEST_RESCHEDULE_TIMEOUT)).isEqualTo(true);
  }

  @Test
  public void shouldSkipCompletedExternally() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey replica1 = createTestProject(project + suffix1);
    Project.NameKey replica2 = createTestProject(project + suffix2);

    // By putting both replicas in the same remote with a single thread,
    // they will be serialized, thus replica1 will fire before replica2.
    setReplicationDestination("foo", Arrays.asList(suffix1, suffix2), ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().refName();

    // Simulate completing the push externally by another node
    String uri1 = gitPath.resolve(replica1.get() + ".git").toString();
    UriLock lock = getUriLock(uri1);
    lock.acquire();
    for (ReplicateRefUpdate update : listWaitingForUri(uri1)) {
      Task task = tasksStorage.new Task(update);
      assertThat(task.start()).isEqualTo(true);
      task.finish();
    }
    lock.release();

    // By waiting for the second push to complete, we know we have
    // waited long enough that the first push should have completed if
    // it were not blocked.
    WaitUtil.waitUntil(() -> checkedGetRef(replica2, sourceRef) != null, TEST_2PUSH_TIMEOUT);

    assertThat(isPushCompleted(replica1, sourceRef, TEST_RESCHEDULE_TIMEOUT)).isEqualTo(false);
  }

  private UriLock getUriLock(String uri) {
    for (ReplicateRefUpdate update : listWaitingForUri(uri)) {
      return tasksStorage.new UriLock(update);
    }
    throw new RuntimeException("Cannot get UriLock for " + uri);
  }

  public List<ReplicateRefUpdate> listWaitingForUri(String uri) {
    List<ReplicateRefUpdate> updates = new ArrayList<>();
    for (ReplicateRefUpdate update : tasksStorage.listWaiting()) {
      if (update.uri.equals(uri)) {
        updates.add(update);
      }
    }
    return updates;
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

  private Ref checkedGetRef(Project.NameKey project, String ref) {
    try (Repository repo = repoManager.openRepository(project)) {
      return checkedGetRef(repo, ref);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in project %s", ref, project);
      return null;
    }
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
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
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
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
