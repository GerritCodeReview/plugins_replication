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

import com.google.common.base.Stopwatch;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final Duration TEST_TIMEMOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 10);

  @Inject private SitePaths sitePaths;
  private Path gitPath;
  private FileBasedConfig config;

  @Override
  public void setUpTestPlugin() throws Exception {
    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.save();

    gitPath = sitePaths.site_path.resolve("git");

    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica");

    Project.NameKey sourceProject = createProject("foo");
    waitUntil(() -> gitPath.resolve(sourceProject + "replica.git").toFile().isDirectory());

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    setReplicationDestination("foo", "replica");

    Project.NameKey targetProject = createProject("projectreplica");

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    waitUntil(() -> getRef(getRepo(targetProject), sourceRef) != null);

    Ref targetBranchRef = getRef(getRepo(targetProject), sourceRef);
    assertThat(targetBranchRef).isNotNull();
    assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
  }

  private Repository getRepo(Project.NameKey targetProject) {
    try {
      return repoManager.openRepository(targetProject);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private Ref getRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().getRef(branchName);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private void setReplicationDestination(String remoteName, String replicaSuffix)
      throws IOException {
    config.setString(
        "remote",
        remoteName,
        "url",
        gitPath.resolve("${name}" + replicaSuffix + ".git").toString());
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    config.save();
    reloadConfig();
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
}
