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

import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationIT extends ReplicationDaemon {
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final int TEST_REPLICATION_RETRY = 1;
  private static final Duration TEST_TIMEOUT =
      Duration.ofSeconds((TEST_REPLICATION_DELAY + TEST_REPLICATION_RETRY * 60) + 1);

  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey sourceProject = createTestProject("foo");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(Project.nameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);

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

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectNameDeleted));
    }

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();
    String metaRef = sourceRef.substring(0, sourceRef.lastIndexOf('/') + 1).concat("meta");

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());

      Ref targetBranchMetaRef = getRef(repo, metaRef);
      assertThat(targetBranchMetaRef).isNotNull();
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
  public void shouldMatchTemplatedURL() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String newRef = "refs/heads/newForTest";
    ObjectId newRefTip = createNewBranchWithoutPush("refs/heads/master", newRef);

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newRef) != null);

      Ref targetBranchRef = getRef(repo, newRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(newRefTip);
    }
  }

  @Test
  public void shouldMatchRealURL() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String newRef = "refs/heads/newForTest";
    ObjectId newRefTip = createNewBranchWithoutPush("refs/heads/master", newRef);

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newRef) != null);

      Ref targetBranchRef = getRef(repo, newRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(newRefTip);
    }
  }

  @Test
  public void pushAllWait() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    ReplicationState state = new ReplicationState(NO_OP);

    Future<?> future =
        plugin
            .getSysInjector()
            .getInstance(PushAll.Factory.class)
            .create(null, new ReplicationFilter(Arrays.asList(project.get())), state, false)
            .schedule(0, TimeUnit.SECONDS);

    future.get();
    state.waitForReplication();
  }

  @Test
  public void pushAllWaitCancelNotRunningTask() throws Exception {
    createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    ReplicationState state = new ReplicationState(NO_OP);

    Future<?> future =
        plugin
            .getSysInjector()
            .getInstance(PushAll.Factory.class)
            .create(null, new ReplicationFilter(Arrays.asList(project.get())), state, false)
            .schedule(0, TimeUnit.SECONDS);

    CountDownLatch latch = new CountDownLatch(1);
    try (ExecutorService service = Executors.newSingleThreadExecutor()) {
      service.execute(
          new Runnable() {
            @Override
            public void run() {
              try {
                future.get();
                state.waitForReplication();
                latch.countDown();
              } catch (Exception e) {
                // fails the test because we don't countDown
              }
            }
          });

      // Cancel the replication task
      waitUntil(() -> getProjectTasks().size() != 0);
      WorkQueue.Task<?> task = getProjectTasks().get(0);
      assertThat(task.getState())
          .isAnyOf(WorkQueue.Task.State.READY, WorkQueue.Task.State.SLEEPING);
      task.cancel(false);

      // Confirm our waiting thread completed
      boolean receivedSignal = latch.await(5, TimeUnit.SECONDS); // FIXME Choose a good timeout
      assertThat(receivedSignal).isTrue();
    }
  }

  private List<WorkQueue.Task<?>> getProjectTasks() {
    return getInstance(WorkQueue.class).getTasks().stream()
        .filter(t -> t instanceof WorkQueue.ProjectTask)
        .collect(Collectors.toList());
  }

  @Test
  public void shouldReplicateHeadUpdate() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
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
  public void shouldReplicateBranchDeletionWhenMirror() throws Exception {
    replicateBranchDeletion(true);
  }

  @Test
  public void shouldNotReplicateBranchDeletionWhenNotMirror() throws Exception {
    replicateBranchDeletion(false);
  }

  private void replicateBranchDeletion(boolean mirror) throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS, mirror);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String branchToDelete = "refs/heads/todelete";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(branchToDelete).create(input);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, branchToDelete) != null);
    }

    gApi.projects().name(project.get()).branch(branchToDelete).delete();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      if (mirror) {
        waitUntil(() -> checkedGetRef(repo, branchToDelete) == null);
      }

      Ref targetBranchRef = getRef(repo, branchToDelete);
      if (mirror) {
        assertThat(targetBranchRef).isNull();
      } else {
        assertThat(targetBranchRef).isNotNull();
      }
    }
  }

  @Test
  public void shouldNotDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject(project + "replica");
    String remoteName = "doNotDrainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    Result pushResult = createChange();
    shutdownDestinations();

    String sourceRef = pushResult.getPatchSet().refName();

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
    Project.NameKey targetProject = createTestProject(project + "replica");
    String remoteName = "drainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    config.setInt("remote", remoteName, "drainQueueAttempts", 2);
    config.save();
    reloadConfig();

    Result pushResult = createChange();
    shutdownDestinations();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldNotDropEventsWhenStarting() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    replicationQueueStop();
    Result pushResult = createChange();
    replicationQueueStart();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldReplicateWithPushBatchSizeSetForRemote() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, 1);
    reloadConfig();

    // creating a change results in 2 refs creation therefore it already qualifies for push in two
    // batches of size 1 each
    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, sourceRef) != null, Duration.ofSeconds(60));

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private void shutdownDestinations() {
    getInstance(DestinationsCollection.class).shutdown();
  }

  private void replicationQueueStart() {
    getReplicationQueueInstance().start();
  }

  private void replicationQueueStop() {
    getReplicationQueueInstance().stop();
  }

  private ReplicationQueue getReplicationQueueInstance() {
    return getInstance(ReplicationQueue.class);
  }

  private ObjectId createNewBranchWithoutPush(String fromBranch, String newBranch)
      throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        RevWalk walk = new RevWalk(repo)) {
      Ref ref = repo.exactRef(fromBranch);
      RevCommit tip = null;
      if (ref != null) {
        tip = walk.parseCommit(ref.getObjectId());
      }
      RefUpdate update = repo.updateRef(newBranch);
      update.setNewObjectId(tip);
      update.update(walk);
      return update.getNewObjectId();
    }
  }
}
