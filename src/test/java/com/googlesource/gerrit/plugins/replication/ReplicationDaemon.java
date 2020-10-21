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
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationDaemon extends LightweightPluginDaemonTest {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected static final Optional<String> ALL_PROJECTS = Optional.empty();

  protected static final int TEST_REPLICATION_DELAY_SECONDS = 1;
  protected static final int TEST_REPLICATION_RETRY_MINUTES = 1;
  protected static final int TEST_PUSH_TIME_SECONDS = 1;
  protected static final Duration TEST_PUSH_TIMEOUT =
      Duration.ofSeconds(TEST_REPLICATION_DELAY_SECONDS + TEST_PUSH_TIME_SECONDS);

  @Inject protected SitePaths sitePaths;
  protected Path gitPath;
  protected FileBasedConfig config;

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
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_RETRY_MINUTES);
  }

  protected void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, int replicationDelay)
      throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project, replicationDelay);
  }

  protected FileBasedConfig setReplicationDestination(
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

  protected Project.NameKey createTestProject(String name) throws Exception {
    return createProject(name);
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

  protected Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  protected void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }
}
