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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.NO_OP;
import static java.util.stream.Collectors.toList;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
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
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final int TEST_REPLICATION_RETRY = 1;
  private static final Duration TEST_TIMEOUT =
      Duration.ofSeconds((TEST_REPLICATION_DELAY + TEST_REPLICATION_RETRY * 60) + 1);

  @Inject private SitePaths sitePaths;
  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
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
    tasksStorage.disableDeleteForTesting(true);
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey sourceProject = createTestProject("foo");

    assertThat(listReplicationTasks("refs/meta/config")).hasSize(1);

    waitUntil(() -> nonEmptyProjectExists(new Project.NameKey(sourceProject + "replica")));

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateProjectDeletion() throws Exception {
    String projectNameDeleted = "project-deleted";
    Project.NameKey replicaProject = createTestProject(projectNameDeleted + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    setProjectDeletionReplication("foo", true);
    reloadConfig();

    ProjectDeletedListener.Event event =
        new ProjectDeletedListener.Event() {
          @Override
          public String getProjectName() {
            return name(projectNameDeleted);
          }

          @Override
          public NotifyHandling getNotify() {
            return NotifyHandling.NONE;
          }
        };

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(event);
    }

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    Project.NameKey targetProject = createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

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

    Project.NameKey targetProject = createTestProject("projectreplica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(listReplicationTasks("refs/heads/(mybranch|master)")).hasSize(2);

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
    Project.NameKey targetProject1 = createTestProject("projectreplica1");
    Project.NameKey targetProject2 = createTestProject("projectreplica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();

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
    createTestProject("projectreplica1");
    createTestProject("projectreplica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
    config.setInt("remote", "foo1", "replicationDelay", TEST_REPLICATION_DELAY * 100);
    config.setInt("remote", "foo2", "replicationDelay", TEST_REPLICATION_DELAY * 100);
    reloadConfig();

    createChange();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS);
  }

  @Test
  public void shouldCreateOneReplicationTaskWhenSchedulingRepoFullSync() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, null, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
  }

  @Test
  public void shouldMatchTemplatedURL() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();
    String expectedURI = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
    }
  }

  @Test
  public void shouldMatchRealURL() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();
    String expectedURI = urlMatch;

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    assertThat(listReplicationTasks(".*")).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
    }
    assertThat(tasksStorage.list()).isNotEmpty();
  }

  @Test
  public void shouldReplicateHeadUpdate() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject("projectreplica");
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
  public void shouldNotDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject("projectreplica");
    String remoteName = "doNotDrainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    Result pushResult = createChange();
    shutdownConfig();

    pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    assertThrows(
        InterruptedException.class,
        () -> {
          try (Repository repo = repoManager.openRepository(targetProject)) {
            waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
          }
        });
  }

  @Test
  public void shouldDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject("projectreplica");
    String remoteName = "drainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    config.setInt("remote", remoteName, "drainQueueAttempts", 2);
    config.save();
    reloadConfig();

    Result pushResult = createChange();
    shutdownConfig();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldFirePendingOnlyToStoredUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject("project" + suffix1);
    Project.NameKey target2 = createTestProject("project" + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().getRefName();

    tasksStorage.disableDeleteForTesting(false);
    listReplicationTasks("refs/changes/\\d*/\\d*/\\d*").stream()
        .filter(task -> remote1.equals(task.remote))
        .forEach(tasksStorage::delete);
    tasksStorage.disableDeleteForTesting(true);

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    assertThat(
            listReplicationTasks("refs/changes/\\d*/\\d*/\\d*").stream()
                .filter(task -> remote2.equals(task.remote))
                .collect(toList()))
        .hasSize(1);

    assertThat(
            listReplicationTasks("refs/changes/\\d*/\\d*/\\d*").stream()
                .filter(task -> remote1.equals(task.remote))
                .collect(toList()))
        .hasSize(0);

    assertThat(isPushCompleted(target2, sourceRef, TEST_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target1, sourceRef, TEST_TIMEOUT)).isEqualTo(false);
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
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_DELAY);
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project, int replicationDelay)
      throws IOException {
    setReplicationDestination(remoteName, Arrays.asList(replicaSuffix), project, replicationDelay);
  }

  private void setReplicationDestination(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {
    setReplicationDestination(remoteName, replicaSuffixes, project, TEST_REPLICATION_DELAY);
  }

  private void setReplicationDestination(
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
    config.setInt("remote", remoteName, "replicationRetry", TEST_REPLICATION_RETRY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.save();
  }

  private void setProjectDeletionReplication(String remoteName, boolean replicateProjectDeletion)
      throws IOException {
    config.setBoolean("remote", remoteName, "replicateProjectDeletions", replicateProjectDeletion);
    config.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }

  private void shutdownConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).shutdown();
  }

  private List<ReplicateRefUpdate> listReplicationTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage.list().stream()
        .filter(task -> refmaskPattern.matcher(task.ref).matches())
        .collect(toList());
  }

  private void cleanupReplicationTasks() throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(storagePath)) {
      for (Path path : files) {
        path.toFile().delete();
      }
    }
  }

  private boolean nonEmptyProjectExists(Project.NameKey name) {
    try (Repository r = repoManager.openRepository(name)) {
      return !r.getAllRefsByPeeledObjectId().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }
}
