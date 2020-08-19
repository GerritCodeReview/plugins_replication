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

import static com.googlesource.gerrit.plugins.replication.ReplicationTasksStorageTest.assertContainsExactly;
import static com.googlesource.gerrit.plugins.replication.ReplicationTasksStorageTest.assertNoIncompleteTasks;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageMPTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH =
      ReplicationTasksStorageTaskTest.getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicationTasksStorage.ReplicateRefUpdate REF_UPDATE =
      new ReplicationTasksStorage.ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);
  protected static final UriUpdates URI_UPDATES = getUriUpdates(REF_UPDATE);

  protected ReplicationTasksStorage nodeA;
  protected ReplicationTasksStorage nodeB;
  protected ReplicationTasksStorage persistedView;
  protected FileSystem fileSystem;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    Path storageSite = fileSystem.getPath("replication_site");
    nodeA = new ReplicationTasksStorage(storageSite);
    nodeB = new ReplicationTasksStorage(storageSite);
    persistedView = new ReplicationTasksStorage(storageSite);
  }

  @After
  public void tearDown() throws Exception {
    persistedView = null;
    nodeA = null;
    nodeB = null;
    fileSystem.close();
  }

  @Test
  public void waitingTaskCanBeCompletedByOtherNode() {
    nodeA.create(REF_UPDATE);

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void resetTaskCanBeCompletedByOtherNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);

    nodeA.reset(URI_UPDATES);
    assertContainsExactly(persistedView.listWaiting(), REF_UPDATE);

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void resetTaskCanBeResetAndCompletedByOtherNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);
    nodeA.reset(URI_UPDATES);
    nodeB.start(URI_UPDATES);

    nodeB.reset(URI_UPDATES);
    assertContainsExactly(persistedView.listWaiting(), REF_UPDATE);

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void resetTaskCanBeResetByOtherNodeAndCompletedByOriginalNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);
    nodeA.reset(URI_UPDATES);
    nodeB.start(URI_UPDATES);
    nodeB.reset(URI_UPDATES);

    nodeA.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeA.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);

    nodeB.resetAll();
    assertContainsExactly(persistedView.listWaiting(), REF_UPDATE);

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeA.finish(URI_UPDATES);
    // Bug: https://crbug.com/gerrit/12973
    // assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNodeFastOriginalNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);
    nodeB.resetAll();

    nodeA.finish(URI_UPDATES);
    assertContainsExactly(persistedView.listWaiting(), REF_UPDATE);

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void canBeResetAllAndCompletedByOtherNodeSlowOriginalNode() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);
    nodeB.resetAll();

    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    ReplicationTasksStorageTest.assertNoIncompleteTasks(persistedView);

    nodeA.finish(URI_UPDATES);
    ReplicationTasksStorageTest.assertNoIncompleteTasks(persistedView);
  }

  @Test
  public void multipleNodesCanReplicateSameRef() {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeA.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);

    nodeB.create(REF_UPDATE);
    nodeB.start(URI_UPDATES);
    assertContainsExactly(persistedView.listRunning(), REF_UPDATE);

    nodeB.finish(URI_UPDATES);
    assertNoIncompleteTasks(persistedView);
  }

  public static UriUpdates getUriUpdates(ReplicationTasksStorage.ReplicateRefUpdate refUpdate) {
    try {
      return TestUriUpdates.create(refUpdate);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate UriUpdates object", e);
    }
  }
}
