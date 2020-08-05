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

import static org.junit.Assert.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageMMTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH =
      ReplicationTasksStorageTest.getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicationTasksStorage.ReplicateRefUpdate REF_UPDATE =
      new ReplicationTasksStorage.ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);

  protected ReplicationTasksStorage nodeA;
  protected ReplicationTasksStorage nodeB;
  protected ReplicationTasksStorage persistedView;
  protected FileSystem fileSystem;
  protected Path storageSite;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    nodeA = new ReplicationTasksStorage(storageSite);
    nodeB = new ReplicationTasksStorage(storageSite);
    persistedView = new ReplicationTasksStorage(storageSite);
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
  public void sameRefPersistedByOtherNodeIsDeduped() {
    nodeA.persist(REF_UPDATE);

    nodeB.persist(REF_UPDATE);
    ReplicationTasksStorageTest.assertContainsExactly(persistedView, REF_UPDATE);
  }

  @Test
  public void persistedRefCanBeCompletedByOtherNode() {
    nodeA.persist(REF_UPDATE);

    nodeB.delete(REF_UPDATE);
    assertTrue(persistedView.list().isEmpty());
  }
}
