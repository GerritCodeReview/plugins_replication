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
  protected static final URIish URISH = getUrish();
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
    assertFalse(whiteBoxIsWaiting(original));

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertFalse(whiteBoxIsWaiting(persistedView));

    original.create();
    assertTrue(whiteBoxIsWaiting(original));
    assertTrue(whiteBoxIsWaiting(persistedView));
  }

  @Test
  public void startedTaskIsRunning() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    assertFalse(whiteBoxIsWaiting(original));
    assertTrue(whiteBoxIsRunning(original));

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertFalse(whiteBoxIsWaiting(persistedView));
    assertTrue(whiteBoxIsRunning(persistedView));
  }

  @Test
  public void resetTaskIsWaiting() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();
    assertTrue(whiteBoxIsWaiting(original));
    assertFalse(whiteBoxIsRunning(original));

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertTrue(whiteBoxIsWaiting(persistedView));
    assertFalse(whiteBoxIsRunning(persistedView));
  }

  @Test
  public void finishedTaskIsRemoved() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.finish();
    assertFalse(whiteBoxIsWaiting(original));
    assertFalse(whiteBoxIsRunning(original));

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    assertFalse(whiteBoxIsWaiting(persistedView));
    assertFalse(whiteBoxIsRunning(persistedView));
  }

  @Test
  public void createWaitingTaskIsDeduped() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    String key = original.create();

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    assertEquals(secondUpdate.create(), key);
    assertTrue(whiteBoxIsWaiting(secondUpdate));
    assertTrue(whiteBoxIsWaiting(original));
  }

  @Test
  public void createTaskWaitingTaskWhileRunning() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    original.create();
    original.start();
    assertTrue(whiteBoxIsRunning(original));

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    secondUpdate.create();

    assertTrue(whiteBoxIsWaiting(original));
    assertTrue(whiteBoxIsWaiting(secondUpdate));

    assertTrue(whiteBoxIsRunning(original));
    assertTrue(whiteBoxIsRunning(secondUpdate));
  }

  @Test
  public void canCompleteTwoTasks() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);
    original.create();
    original.start();
    original.finish();

    Task secondUpdate = tasksStorage.new Task(REF_UPDATE);
    secondUpdate.create();
    assertTrue(whiteBoxIsWaiting(secondUpdate));
    secondUpdate.start();
    assertTrue(whiteBoxIsRunning(secondUpdate));
    secondUpdate.finish();
    assertFalse(whiteBoxIsWaiting(secondUpdate));
    assertFalse(whiteBoxIsRunning(secondUpdate));
  }

  @Test
  public void canStartResetTask() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.start();
    assertTrue(whiteBoxIsRunning(persistedView));
    assertTrue(whiteBoxIsRunning(original));
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
    assertTrue(whiteBoxIsWaiting(persistedView));
    assertTrue(whiteBoxIsWaiting(original));
  }

  @Test
  public void illegalFinishNonRunningTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);
    task.finish(); // Exceptions are squashed and a message is logged
    assertFalse(whiteBoxIsWaiting(task));
    assertFalse(whiteBoxIsRunning(task));
  }

  @Test
  public void illegalResetNonRunningTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);
    task.reset(); // Exceptions are squashed and a message is logged
    assertFalse(whiteBoxIsWaiting(task));
    assertFalse(whiteBoxIsRunning(task));
  }

  @Test
  public void illegalResetFinishedTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);

    task.create();
    task.start();
    task.finish();
    task.reset(); // Exceptions are squashed and a message is logged
    assertFalse(whiteBoxIsWaiting(task));
    assertFalse(whiteBoxIsRunning(task));
  }

  @Test
  public void illegalFinishFinishedTaskIsGraceful() throws Exception {
    Task task = tasksStorage.new Task(REF_UPDATE);

    task.create();
    task.start();
    task.finish();
    task.finish(); // Exceptions are squashed and a message is logged
    assertFalse(whiteBoxIsWaiting(task));
    assertFalse(whiteBoxIsRunning(task));
  }

  @Test
  public void illegalFinishResetTaskIsGraceful() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.finish();
    assertTrue(whiteBoxIsWaiting(persistedView));
  }

  @Test
  public void illegalResetResetTaskIsGraceful() throws Exception {
    Task original = tasksStorage.new Task(REF_UPDATE);

    original.create();
    original.start();
    original.reset();

    Task persistedView = tasksStorage.new Task(REF_UPDATE);
    persistedView.reset();
    assertTrue(whiteBoxIsWaiting(persistedView));
  }

  private boolean whiteBoxIsRunning(Task task) {
    return Files.exists(task.running);
  }

  private boolean whiteBoxIsWaiting(Task task) {
    return Files.exists(task.waiting);
  }

  public static URIish getUrish() {
    try {
      return new URIish("http://example.com/" + PROJECT + ".git");
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate URIish object", e);
    }
  }
}
