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
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Key;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
  private static final int TEST_REPLICATION_DELAY = 2;
  private static final Duration TEST_TIMEMOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 10);

  @Inject private SitePaths sitePaths;
  private Path pluginDataDir;
  private Path gitPath;
  private Path storagePath;
  private FileBasedConfig config;

  @Override
  public void setUpTestPlugin() throws Exception {
    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.save();

    gitPath = sitePaths.site_path.resolve("git");

    super.setUpTestPlugin();

    pluginDataDir = plugin.getSysInjector().getInstance(Key.get(Path.class, PluginData.class));
    storagePath = pluginDataDir.resolve("ref-updates");
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", TEST_REPLICATION_DELAY);

    Project.NameKey sourceProject = createProject("foo");

    String[] replicationTasks = storagePath.toFile().list();
    assertThat(replicationTasks).hasLength(2); // refs/heads/master and /refs/meta/config

    waitUntil(() -> gitPath.resolve(sourceProject + "replica.git").toFile().isDirectory());

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    Project.NameKey targetProject = createProject("projectreplica");

    setReplicationDestination("foo", "replica", TEST_REPLICATION_DELAY);

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    String[] replicationTasks = storagePath.toFile().list();
    assertThat(replicationTasks).hasLength(1);

    waitUntil(() -> getRef(getRepo(targetProject), sourceRef) != null);

    Ref targetBranchRef = getRef(getRepo(targetProject), sourceRef);
    assertThat(targetBranchRef).isNotNull();
    assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createProject("projectreplica1");
    Project.NameKey targetProject2 = createProject("projectreplica2");

    setReplicationDestination("foo1", "replica1", TEST_REPLICATION_DELAY);
    setReplicationDestination("foo2", "replica2", TEST_REPLICATION_DELAY);

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    String[] replicationTasks = storagePath.toFile().list();
    assertThat(replicationTasks).hasLength(2);

    waitUntil(
        () ->
            (getRef(getRepo(targetProject1), sourceRef) != null
                && getRef(getRepo(targetProject2), sourceRef) != null));

    Ref targetBranchRef1 = getRef(getRepo(targetProject1), sourceRef);
    assertThat(targetBranchRef1).isNotNull();
    assertThat(targetBranchRef1.getObjectId()).isEqualTo(sourceCommit.getId());

    Ref targetBranchRef2 = getRef(getRepo(targetProject2), sourceRef);
    assertThat(targetBranchRef2).isNotNull();
    assertThat(targetBranchRef2.getObjectId()).isEqualTo(sourceCommit.getId());
  }

  @Test
  public void shouldCreateIndividualResplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");
    createProject("projectreplica1");
    createProject("projectreplica2");

    setReplicationDestination("foo1", replicaSuffixes, TEST_REPLICATION_DELAY);
    setReplicationDestination("foo2", replicaSuffixes, TEST_REPLICATION_DELAY);

    createChange();

    assertThat(storagePath.toFile().list()).hasLength(4);
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

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, int replicationDelay) throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), replicationDelay);
  }

  private void setReplicationDestination(
      String remoteName, List<String> replicaSuffixes, int replicationDelay) throws IOException {
    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setInt("remote", remoteName, "replicationDelay", replicationDelay);
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
