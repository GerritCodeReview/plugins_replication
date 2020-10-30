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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.Task;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageTaskTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH = getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicateRefUpdate REF_UPDATE =
      new ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);

  protected ReplicationTasksStorage tasksStorage;
  protected FileSystem fileSystem;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    tasksStorage = new ReplicationTasksStorage(fileSystem.getPath("replication_site"));
  }

  @After
  public void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  public void createdTaskIsWaiting() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    assertNotWaiting(original);

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertNotWaiting(persistedView);

    original.create();
    assertIsWaiting(original);
    assertIsWaiting(persistedView);
  }

  @Test
  public void startedTaskIsRunning() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    assertNotWaiting(original);
    assertIsRunning(original);

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertNotWaiting(persistedView);
    assertIsRunning(persistedView);
  }

  @Test
  public void resetTaskIsWaiting() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();
    assertIsWaiting(original);
    assertNotRunning(original);

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertIsWaiting(persistedView);
    assertNotRunning(persistedView);
  }

  @Test
  public void finishedTaskIsRemoved() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.finish();
    assertNotWaiting(original);
    assertNotRunning(original);

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertNotWaiting(persistedView);
    assertNotRunning(persistedView);
  }

  @Test
  public void createWaitingTaskIsDeduped() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    String key = original.create();

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    assertEquals(secondUpdate.create(), key);
    assertIsWaiting(secondUpdate);
    assertIsWaiting(original);
  }

  @Test
  public void createWaitingTaskWhileTaskIsRunning() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    original.create();
    original.start();
    assertIsRunning(original);

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    secondUpdate.create();

    assertIsWaiting(secondUpdate);
    assertIsRunning(original);
  }

  @Test
  public void canCompleteTwoTasks() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    original.create();
    original.start();
    original.finish();

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    secondUpdate.create();
    assertIsWaiting(secondUpdate);
    secondUpdate.start();
    assertIsRunning(secondUpdate);
    secondUpdate.finish();
    assertNotWaiting(secondUpdate);
    assertNotRunning(secondUpdate);
  }

  @Test
  public void canStartResetTask() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.start();
    assertIsRunning(persistedView);
    assertIsRunning(original);
  }

  @Test
  public void canResetPreviouslyResetAndStartedTask() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.start();
    persistedView.reset();
    assertIsWaiting(persistedView);
    assertIsWaiting(original);
  }

  @Test
  public void multipleActorsCanUpdateSameTask() throws Exception {
    Task persistedView = tasksStorage.new Task(REF_UPDATE);

    Task fromEvent = tasksStorage.new Task(REF_UPDATE);
    fromEvent.create();
    assertIsWaiting(persistedView);

    Task fromPusherStart = tasksStorage.new Task(REF_UPDATE);
    fromPusherStart.start();
    assertIsRunning(persistedView);

    Task fromPusherEnd = tasksStorage.new Task(REF_UPDATE);
    fromPusherEnd.finish();
    assertNotWaiting(persistedView);
    assertNotRunning(persistedView);
  }

  @Test
  public void canHaveTwoWaitingTasksForDifferentRefs() throws Exception {
    Task updateA = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, "refA", URISH, REMOTE));
    Task updateB = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, "refB", URISH, REMOTE));
    updateA.create();
    updateB.create();
    assertIsWaiting(updateA);
    assertIsWaiting(updateB);
  }

  @Test
  public void canHaveTwoRunningTasksForDifferentRefs() throws Exception {
    Task updateA = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, "refA", URISH, REMOTE));
    Task updateB = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, "refB", URISH, REMOTE));
    updateA.create();
    updateB.create();
    updateA.start();
    updateB.start();
    assertIsRunning(updateA);
    assertIsRunning(updateB);
  }

  @Test
  public void canHaveTwoWaitingTasksForDifferentProjects() throws Exception {
    Task updateA =
        tasksStorage
        .new Task(
            new ReplicateRefUpdate(
                "projectA", REF, getUrish("http://example.com/projectA.git"), REMOTE));
    Task updateB =
        tasksStorage
        .new Task(
            new ReplicateRefUpdate(
                "projectB", REF, getUrish("http://example.com/projectB.git"), REMOTE));
    updateA.create();
    updateB.create();
    assertIsWaiting(updateA);
    assertIsWaiting(updateB);
  }

  @Test
  public void canHaveTwoRunningTasksForDifferentProjects() throws Exception {
    Task updateA =
        tasksStorage
        .new Task(
            new ReplicateRefUpdate(
                "projectA", REF, getUrish("http://example.com/projectA.git"), REMOTE));
    Task updateB =
        tasksStorage
        .new Task(
            new ReplicateRefUpdate(
                "projectB", REF, getUrish("http://example.com/projectB.git"), REMOTE));
    updateA.create();
    updateB.create();
    updateA.start();
    updateB.start();
    assertIsRunning(updateA);
    assertIsRunning(updateB);
  }

  @Test
  public void canHaveTwoWaitingTasksForDifferentRemotes() throws Exception {
    Task updateA = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, REF, URISH, "remoteA"));
    Task updateB = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, REF, URISH, "remoteB"));
    updateA.create();
    updateB.create();
    assertIsWaiting(updateA);
    assertIsWaiting(updateB);
  }

  @Test
  public void canHaveTwoRunningTasksForDifferentRemotes() throws Exception {
    Task updateA = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, REF, URISH, "remoteA"));
    Task updateB = tasksStorage.new Task(new ReplicateRefUpdate(PROJECT, REF, URISH, "remoteB"));
    updateA.create();
    updateB.create();
    updateA.start();
    updateB.start();
    assertIsRunning(updateA);
    assertIsRunning(updateB);
  }

  @Test
  public void illegalFinishNonRunningTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);
    task.finish();
    assertNotWaiting(task);
    assertNotRunning(task);
  }

  @Test
  public void illegalResetNonRunningTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);
    task.reset();
    assertNotWaiting(task);
    assertNotRunning(task);
  }

  @Test
  public void illegalResetFinishedTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);

    task.create();
    task.start();
    task.finish();
    task.reset();
    assertNotWaiting(task);
    assertNotRunning(task);
  }

  @Test
  public void illegalFinishFinishedTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);

    task.create();
    task.start();
    task.finish();
    task.finish();
    assertNotWaiting(task);
    assertNotRunning(task);
  }

  @Test
  public void illegalFinishResetTaskIsGraceful() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.finish();
    assertIsWaiting(persistedView);
  }

  @Test
  public void illegalResetResetTaskIsGraceful() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.reset();
    assertIsWaiting(persistedView);
  }

  protected static void assertIsWaiting(Task task) {
    assertTrue(task.isWaiting());
  }

  protected static void assertNotWaiting(Task task) {
    assertFalse(task.isWaiting());
  }

  protected static void assertIsRunning(Task task) {
    assertTrue(whiteBoxIsRunning(task));
  }

  protected static void assertNotRunning(Task task) {
    assertFalse(whiteBoxIsRunning(task));
  }

  private static boolean whiteBoxIsRunning(Task task) {
    return Files.exists(task.running);
  }

  public static URIish getUrish(String uri) {
    try {
      return new URIish(uri);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate URIish object", e);
    }
  }
}
