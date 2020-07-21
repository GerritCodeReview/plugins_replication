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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH = getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicateRefUpdate REF_UPDATE =
      new ReplicateRefUpdate(PROJECT, REF, URISH, REMOTE);

  protected ReplicationTasksStorage original;
  protected ReplicationTasksStorage persistedView;
  protected FileSystem fileSystem;
  protected Path storageSite;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    original = new ReplicationTasksStorage(storageSite);
    persistedView = new ReplicationTasksStorage(storageSite);
  }

  @After
  public void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  public void canListEmptyStorage() throws Exception {
    assertThat(persistedView.list()).isEmpty();
  }

  @Test
  public void canListPersistedUpdate() throws Exception {
    original.persist(REF_UPDATE);
    assertTrue(equals(persistedView.list().get(0), REF_UPDATE));
  }

  @Test
  public void canDeletePersistedUpdate() throws Exception {
    original.persist(REF_UPDATE);
    persistedView.delete(REF_UPDATE);
    assertThat(persistedView.list()).isEmpty();
  }

  @Test
  public void sameRefUpdatePersistedTwiceIsStoredOnce() throws Exception {
    String key = original.persist(REF_UPDATE);
    String secondKey = original.persist(REF_UPDATE);
    assertEquals(key, secondKey);
    assertEquals(persistedView.list().size(), 1);
    assertTrue(equals(persistedView.list().get(0), REF_UPDATE));
  }

  @Test
  public void canPersistDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"),
            REMOTE); // uses ssh not http

    String keyA = original.persist(REF_UPDATE);
    String keyB = original.persist(updateB);
    assertEquals(persistedView.list().size(), 2);
    assertNotEquals(keyA, keyB);

    persistedView.delete(REF_UPDATE);
    assertEquals(persistedView.list().size(), 1);
    assertTrue(equals(persistedView.list().get(0), updateB));
    assertTrue(equals(original.list().get(0), updateB));

    original.delete(updateB);
    assertEquals(persistedView.list().size(), 0);
    assertEquals(original.list().size(), 0);
  }

  @Test
  public void differentUrisPersistedTwiceIsStoredOnce() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"),
            REMOTE); // uses ssh not http

    original.persist(REF_UPDATE);
    original.persist(REF_UPDATE);
    original.persist(updateB);
    original.persist(updateB);
    assertEquals(persistedView.list().size(), 2);
    assertEquals(original.list().size(), 2);
  }

  @Test
  public void canPersistMulipleRefsForSameUri() throws Exception {
    ReplicateRefUpdate refA = new ReplicateRefUpdate(PROJECT, "refA", URISH, REMOTE);
    ReplicateRefUpdate refB = new ReplicateRefUpdate(PROJECT, "refB", URISH, REMOTE);

    String keyA = original.persist(refA);
    String keyB = original.persist(refB);
    assertEquals(persistedView.list().size(), 2);
    assertNotEquals(keyA, keyB);

    persistedView.delete(refA);
    assertEquals(persistedView.list().size(), 1);
    assertEquals(original.list().size(), 1);
    assertTrue(equals(persistedView.list().get(0), refB));
    assertTrue(equals(original.list().get(0), refB));

    original.delete(refB);
    assertEquals(persistedView.list().size(), 0);
    assertEquals(original.list().size(), 0);
  }

  @Test
  public void illegalDeleteNonPersistedIsGraceful() throws Exception {
    persistedView.delete(REF_UPDATE);
  }

  @Test
  public void illegalDoubleDeleteIsGraceful() throws Exception {
    original.persist(REF_UPDATE);
    persistedView.delete(REF_UPDATE);
    original.delete(REF_UPDATE);
  }

  @Test
  public void illegalDoubleDeleteDifferentUriIsGraceful() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"),
            REMOTE); // uses ssh not http

    original.persist(REF_UPDATE);
    original.persist(updateB);
    persistedView.delete(REF_UPDATE);
    original.delete(REF_UPDATE);
    original.delete(updateB);
    persistedView.delete(updateB);
    assertEquals(persistedView.list().size(), 0);
  }

  private boolean equals(ReplicateRefUpdate one, ReplicateRefUpdate two) {
    return (one == null && two == null)
        || (one != null
            && two != null
            && Objects.equals(one.project, two.project)
            && Objects.equals(one.ref, two.ref)
            && Objects.equals(one.remote, two.remote)
            && Objects.equals(one.uri, two.uri));
  }

  public static URIish getUrish(String uri) {
    try {
      return new URIish(uri);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate URIish object", e);
    }
  }
}
