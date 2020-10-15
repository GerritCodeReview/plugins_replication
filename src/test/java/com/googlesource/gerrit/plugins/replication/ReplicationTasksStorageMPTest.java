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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageMPTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH =
      ReplicationTasksStorageTest.getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicateRefUpdate REF_UPDATE =
      new ReplicationTasksStorage.ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);
  protected static final UriUpdates URI_UPDATE = getUriUpdates(REF_UPDATE);
  protected static final long SECONDS_TASK_STALE_AGE = 2;

  protected FileSystem fileSystem;
  protected Path storageSite;
  protected ReplicationTasksStorage nodeA;
  protected ReplicationTasksStorage nodeB;
  protected ReplicationTasksStorage nodeC;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    nodeA = new ReplicationTasksStorage(storageSite, SECONDS_TASK_STALE_AGE);
    nodeB = new ReplicationTasksStorage(storageSite, SECONDS_TASK_STALE_AGE);
    nodeC = new ReplicationTasksStorage(storageSite, SECONDS_TASK_STALE_AGE);
  }

  @After
  public void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  public void staleTasksByOtherNodeAreRecovered() throws Exception {
    nodeA.create(REF_UPDATE);
    nodeA.start(URI_UPDATE);
    ReplicateRefUpdate secondRefUpdate =
        new ReplicationTasksStorage.ReplicateRefUpdate(PROJECT, REF, URISH, "remoteB");
    UriUpdates secondUpdate = TestUriUpdates.create(secondRefUpdate);
    TimeUnit.SECONDS.sleep(SECONDS_TASK_STALE_AGE + 1);

    nodeB.create(secondRefUpdate);
    nodeB.start(secondUpdate);
    assertContainsExactly(nodeC.listRunning(), secondRefUpdate);
    assertContainsExactly(nodeC.listWaiting(), REF_UPDATE);
  }

  public static UriUpdates getUriUpdates(ReplicationTasksStorage.ReplicateRefUpdate refUpdate) {
    try {
      return TestUriUpdates.create(refUpdate);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate UriUpdates object", e);
    }
  }
}
