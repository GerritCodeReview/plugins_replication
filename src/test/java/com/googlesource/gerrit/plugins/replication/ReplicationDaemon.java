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

import static java.util.stream.Collectors.toList;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

/**
 * This class can be extended by any Replication*IT class and provides common setup and helper
 * methods.
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationDaemon extends LightweightPluginDaemonTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected static final Optional<String> ALL_PROJECTS = Optional.empty();

  protected static final int TEST_REPLICATION_DELAY_SECONDS = 1;
  protected static final int TEST_LONG_REPLICATION_DELAY_SECONDS = 30;
  protected static final int TEST_REPLICATION_RETRY_MINUTES = 1;
  protected static final int TEST_PUSH_TIME_SECONDS = 1;
  protected static final int TEST_PROJECT_CREATION_SECONDS = 10;
  protected static final Duration TEST_PUSH_TIMEOUT =
      Duration.ofSeconds(TEST_REPLICATION_DELAY_SECONDS + TEST_PUSH_TIME_SECONDS);
  protected static final Duration TEST_PUSH_TIMEOUT_LONG =
      Duration.ofSeconds(TEST_LONG_REPLICATION_DELAY_SECONDS + TEST_PUSH_TIME_SECONDS);
  protected static final Duration TEST_NEW_PROJECT_TIMEOUT =
      Duration.ofSeconds(
          (TEST_REPLICATION_DELAY_SECONDS + TEST_REPLICATION_RETRY_MINUTES * 60)
              + TEST_PROJECT_CREATION_SECONDS);

  @Inject protected SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  protected Path gitPath;
  protected FileBasedConfig config;

  protected void setDistributionInterval(int interval) throws IOException {
    config.setInt("replication", null, "distributionInterval", interval);
    config.save();
  }

  protected String getProjectUri(Project.NameKey project) throws Exception {
    return ((LocalDiskRepositoryManager) repoManager)
        .getBasePath(project)
        .resolve(project.get() + ".git")
        .toString();
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_DELAY_SECONDS);
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, Integer pushBatchSize)
      throws IOException {
    setReplicationDestination(
        remoteName,
        Arrays.asList(replicaSuffix),
        project,
        TEST_REPLICATION_DELAY_SECONDS,
        false,
        Optional.ofNullable(pushBatchSize));
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, boolean mirror)
      throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_DELAY_SECONDS, mirror);
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, int replicationDelay)
      throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project, replicationDelay);
  }

  protected void setReplicationDestination(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay)
      throws IOException {
    setReplicationDestination(remoteName, replicaSuffixes, project, replicationDelay, false);
  }

  protected void setReplicationDestination(
      String remoteName,
      String replicaSuffix,
      Optional<String> project,
      int replicationDelay,
      boolean mirror)
      throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, replicationDelay, mirror);
  }

  protected void setReplicationDestination(
      String remoteName,
      String replicaSuffix,
      Optional<String> project,
      int replicationDelay,
      boolean mirror,
      Optional<Integer> pushBatchSize)
      throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, replicationDelay, mirror, pushBatchSize);
  }

  protected void setReplicationDestination(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay,
      boolean mirror)
      throws IOException {
    setReplicationDestination(
        remoteName, replicaSuffixes, project, replicationDelay, mirror, Optional.empty());
  }

  protected void setReplicationDestination(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay,
      boolean mirror,
      Optional<Integer> pushBatchSize)
      throws IOException {
    setReplicationDestination(
        config, remoteName, replicaSuffixes, project, replicationDelay, mirror, pushBatchSize);
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  protected void setReplicationDestination(
      FileBasedConfig config,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay)
      throws IOException {
    setReplicationDestination(
        config, null, replicaSuffixes, project, replicationDelay, false, Optional.empty());
  }

  protected void setReplicationDestination(
      FileBasedConfig remoteConfig,
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay,
      boolean mirror,
      Optional<Integer> pushBatchSize)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    remoteConfig.setStringList("remote", remoteName, "url", replicaUrls);
    remoteConfig.setInt("remote", remoteName, "replicationDelay", replicationDelay);
    remoteConfig.setInt("remote", remoteName, "replicationRetry", TEST_REPLICATION_RETRY_MINUTES);
    remoteConfig.setBoolean("remote", remoteName, "mirror", mirror);
    project.ifPresent(prj -> remoteConfig.setString("remote", remoteName, "projects", prj));
    pushBatchSize.ifPresent(pbs -> remoteConfig.setInt("remote", remoteName, "pushBatchSize", pbs));
    remoteConfig.save();
  }

  protected Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  protected boolean isPushCompleted(Project.NameKey project, String ref, Duration timeOut) {
    try (Repository repo = repoManager.openRepository(project)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, ref) != null, timeOut);
      return true;
    } catch (InterruptedException e) {
      return false;
    } catch (Exception e) {
      throw new RuntimeException("Cannot open repo for project" + project, e);
    }
  }

  protected boolean isPushCompleted(Map<Project.NameKey, String> refsByProject, Duration timeOut) {
    try {
      WaitUtil.waitUntil(
          () -> {
            Iterator<Map.Entry<Project.NameKey, String>> iterator =
                refsByProject.entrySet().iterator();
            while (iterator.hasNext()) {
              Map.Entry<Project.NameKey, String> entry = iterator.next();
              try (Repository repo = repoManager.openRepository(entry.getKey())) {
                if (checkedGetRef(repo, entry.getValue()) != null) {
                  iterator.remove();
                }
              } catch (IOException e) {
                throw new RuntimeException("Cannot open repo for project" + entry.getKey(), e);
              }
            }
            return refsByProject.isEmpty();
          },
          timeOut);
    } catch (InterruptedException e) {
      return false;
    }
    return true;
  }

  @Nullable
  protected Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  protected Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  protected void reloadConfig() {
    getAutoReloadConfigDecoratorInstance().reload();
  }

  protected AutoReloadConfigDecorator getAutoReloadConfigDecoratorInstance() {
    return getInstance(AutoReloadConfigDecorator.class);
  }

  protected <T> T getInstance(Class<T> classObj) {
    return plugin.getSysInjector().getInstance(classObj);
  }

  protected boolean nonEmptyProjectExists(Project.NameKey name) {
    try (Repository r = repoManager.openRepository(name)) {
      return !r.getAllRefsByPeeledObjectId().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  protected void initConfig() throws IOException {
    if (config == null) {
      gitPath = sitePaths.site_path.resolve("git");
      config =
          new FileBasedConfig(
              sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
      config.save();
    }
  }

  protected ProjectDeletedListener.Event projectDeletedEvent(String projectNameDeleted) {
    return new ProjectDeletedListener.Event() {
      @Override
      public String getProjectName() {
        return projectNameDeleted;
      }

      @Override
      public NotifyHandling getNotify() {
        return NotifyHandling.NONE;
      }
    };
  }

  protected void setProjectDeletionReplication(String remoteName, boolean replicateProjectDeletion)
      throws IOException {
    config.setBoolean("remote", remoteName, "replicateProjectDeletions", replicateProjectDeletion);
    config.save();
  }
}
