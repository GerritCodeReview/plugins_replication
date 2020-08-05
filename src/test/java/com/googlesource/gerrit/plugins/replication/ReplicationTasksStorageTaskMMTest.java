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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageTaskMMTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH =
      ReplicationTasksStorageTest.getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicationTasksStorage.ReplicateRefUpdate REF_UPDATE =
      new ReplicationTasksStorage.ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);

  protected FileSystem fileSystem;
  protected Path storageSite;
  protected ReplicationTasksStorage nodeA;
  protected ReplicationTasksStorage nodeB;
  protected ReplicationTasksStorage.Task taskA;
  protected ReplicationTasksStorage.Task taskB;
  protected ReplicationTasksStorage persistedView;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    nodeA = new ReplicationTasksStorage(storageSite);
    nodeB = new ReplicationTasksStorage(storageSite);
    persistedView = new ReplicationTasksStorage(storageSite);
    taskA = nodeA.new Task(REF_UPDATE);
    taskB = nodeB.new Task(REF_UPDATE);
  }

  @After
  public void tearDown() throws Exception {
    persistedView = null;
    nodeB = null;
    nodeA = null;
    storageSite = null;
    fileSystem.close();
  }

  @Test
  public void waitingTaskCanBeCompletedByOtherNode() {
    taskA.create();

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
  }

  @Test
  public void resetTaskCanBeCompletedByOtherNode() {
    taskA.create();
    taskA.start();

    taskA.reset();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
  }

  @Test
  public void retryCanBeRetriedAndCompletedByOtherNode() {
    taskA.create();
    taskA.start();
    taskA.reset();
    taskB.start();

    taskB.reset();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
  }

  @Test
  public void retryCanBeRetriedOtherNodeAndCompletedByOriginalNode() {
    taskA.create();
    taskA.start();
    taskA.reset();
    taskB.start();
    taskB.reset();

    taskA.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskA.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNode() {
    taskA.create();
    taskA.start();

    nodeB.resetAll();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskA.finish();
    //  Bug: https://crbug.com/gerrit/12973
    // ReplicationTasksStorageTaskTest.assertIsRunning(taskB);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
    ReplicationTasksStorageTaskTest.assertNotRunning(taskB);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskB);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNodeFastOriginalNode() {
    taskA.create();
    taskA.start();
    nodeB.resetAll();

    taskA.finish();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNodeSlowOriginalNode() {
    taskA.create();
    taskA.start();
    nodeB.resetAll();

    taskB.start();
    ReplicationTasksStorageTaskTest.assertIsRunning(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);

    taskA.finish();
    ReplicationTasksStorageTaskTest.assertNotWaiting(taskA);
    ReplicationTasksStorageTaskTest.assertNotRunning(taskA);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalRunningTaskResetByOtherNodeIsGraceful() {
    taskA.create();
    taskA.start();
    taskB.reset();
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalRunningTaskFinishByOtherNodeIsGraceful() {
    taskA.create();
    taskA.start();
    taskB.finish();
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalResetTaskResetByOtherNodeIsGraceful() {
    taskA.create();
    taskA.start();
    taskA.reset();
    taskB.reset();
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalFinishByOtherNodeIsGraceful() {
    taskA.create();
    taskA.start();
    taskA.finish();
    taskB.finish();
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalFinishByOtherNodeAfterResetIsGraceful() {
    taskA.create();
    taskA.start();

    taskA.reset();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);

    taskB.finish();
    ReplicationTasksStorageTaskTest.assertIsWaiting(taskA);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalResetByOtherNodeAfterFinishIsGraceful() {
    taskA.create();
    taskA.start();
    taskA.finish();
    taskB.reset();
  }
}
