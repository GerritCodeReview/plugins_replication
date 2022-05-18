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

import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdateTypeAdapterFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.Task;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
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
      ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, REMOTE);

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
    Task updateA =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of("refA"), URISH, REMOTE));
    Task updateB =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of("refB"), URISH, REMOTE));
    updateA.create();
    updateB.create();
    assertIsWaiting(updateA);
    assertIsWaiting(updateB);
  }

  @Test
  public void canHaveTwoRunningTasksForDifferentRefs() throws Exception {
    Task updateA =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of("refA"), URISH, REMOTE));
    Task updateB =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of("refB"), URISH, REMOTE));
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
            ReplicateRefUpdate.create(
                "projectA", Set.of(REF), getUrish("http://example.com/projectA.git"), REMOTE));
    Task updateB =
        tasksStorage
        .new Task(
            ReplicateRefUpdate.create(
                "projectB", Set.of(REF), getUrish("http://example.com/projectB.git"), REMOTE));
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
            ReplicateRefUpdate.create(
                "projectA", Set.of(REF), getUrish("http://example.com/projectA.git"), REMOTE));
    Task updateB =
        tasksStorage
        .new Task(
            ReplicateRefUpdate.create(
                "projectB", Set.of(REF), getUrish("http://example.com/projectB.git"), REMOTE));
    updateA.create();
    updateB.create();
    updateA.start();
    updateB.start();
    assertIsRunning(updateA);
    assertIsRunning(updateB);
  }

  @Test
  public void canHaveTwoWaitingTasksForDifferentRemotes() throws Exception {
    Task updateA =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, "remoteA"));
    Task updateB =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, "remoteB"));
    updateA.create();
    updateB.create();
    assertIsWaiting(updateA);
    assertIsWaiting(updateB);
  }

  @Test
  public void canHaveTwoRunningTasksForDifferentRemotes() throws Exception {
    Task updateA =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, "remoteA"));
    Task updateB =
        tasksStorage.new Task(ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, "remoteB"));
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

  @Test
  public void writeReplicateRefUpdateTypeAdapter() throws Exception {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new ReplicateRefUpdateTypeAdapterFactory())
            .create();
    ReplicateRefUpdate update =
        ReplicateRefUpdate.create(
            "someProject",
            ImmutableSet.of("ref1"),
            new URIish("git://host1/someRepo.git"),
            "someRemote");
    assertEquals(
        gson.toJson(update),
        "{\"project\":\"someProject\",\"refs\":[\"ref1\"],\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}");
    ReplicateRefUpdate update2 =
        ReplicateRefUpdate.create(
            "someProject",
            ImmutableSet.of("ref1", "ref2"),
            new URIish("git://host1/someRepo.git"),
            "someRemote");
    assertEquals(
        gson.toJson(update2),
        "{\"project\":\"someProject\",\"refs\":[\"ref1\",\"ref2\"],\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}");
  }

  @Test
  public void ReadReplicateRefUpdateTypeAdapter() throws Exception {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new ReplicateRefUpdateTypeAdapterFactory())
            .create();
    ReplicateRefUpdate update =
        ReplicateRefUpdate.create(
            "someProject",
            ImmutableSet.of("ref1"),
            new URIish("git://host1/someRepo.git"),
            "someRemote");
    assertEquals(
        gson.fromJson(
            "{\"project\":\"someProject\",\"refs\":[\"ref1\"],\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}",
            ReplicateRefUpdate.class),
        update);
    ReplicateRefUpdate update2 =
        ReplicateRefUpdate.create(
            "someProject",
            ImmutableSet.of("ref1", "ref2"),
            new URIish("git://host1/someRepo.git"),
            "someRemote");
    ReplicateRefUpdate restoredUpdate2 =
        gson.fromJson(
            "{\"project\":\"someProject\",\"refs\":[\"ref1\",\"ref2\"],\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}",
            ReplicateRefUpdate.class);
    // ReplicateRefUpdate.sha() might be different for the compared objects, since
    // the order of refs() might differ.
    assertEquals(update2.project(), restoredUpdate2.project());
    assertEquals(update2.uri(), restoredUpdate2.uri());
    assertEquals(update2.remote(), restoredUpdate2.remote());
    assertEquals(update2.refs(), restoredUpdate2.refs());
  }

  @Test
  public void ReplicateRefUpdateTypeAdapter_FailWithUnknownField() throws Exception {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new ReplicateRefUpdateTypeAdapterFactory())
            .create();
    assertThrows(
        JsonSyntaxException.class,
        () -> gson.fromJson("{\"unknownKey\":\"someValue\"}", ReplicateRefUpdate.class));
  }

  @Test
  public void ReadOldFormatReplicateRefUpdateTypeAdapter() throws Exception {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new ReplicateRefUpdateTypeAdapterFactory())
            .create();
    ReplicateRefUpdate update =
        ReplicateRefUpdate.create(
            "someProject",
            ImmutableSet.of("ref1"),
            new URIish("git://host1/someRepo.git"),
            "someRemote");
    assertEquals(
        gson.fromJson(
            "{\"project\":\"someProject\",\"ref\":\"ref1\",\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}",
            ReplicateRefUpdate.class),
        update);
  }

  @SuppressWarnings("deprecation")
  @Test
  public void schedulingTaskFromOldFormatTasksIsSuccessful() throws Exception {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new ReplicateRefUpdateTypeAdapterFactory())
            .create();
    ReplicateRefUpdate update =
        gson.fromJson(
            "{\"project\":\"someProject\",\"ref\":\"ref1\",\"uri\":\"git://host1/someRepo.git\",\"remote\":\"someRemote\"}",
            ReplicateRefUpdate.class);

    String oldTaskKey =
        ObjectId.fromRaw(
                Hashing.sha1()
                    .hashString("someProject\nref1\ngit://host1/someRepo.git\nsomeRemote", UTF_8)
                    .asBytes())
            .name();
    String json = gson.toJson(update) + "\n";
    Path tmp =
        Files.createTempFile(
            Files.createDirectories(fileSystem.getPath("replication_site").resolve("building")),
            oldTaskKey,
            null);
    Files.write(tmp, json.getBytes(UTF_8));

    Task task = tasksStorage.new Task(update);
    task.rename(tmp, task.waiting);

    task.start();
    assertIsRunning(task);
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
