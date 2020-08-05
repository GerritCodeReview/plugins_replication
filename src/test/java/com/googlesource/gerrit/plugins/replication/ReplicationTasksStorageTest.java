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

  protected ReplicationTasksStorage storage;
  protected FileSystem fileSystem;
  protected Path storageSite;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    storage = new ReplicationTasksStorage(storageSite);
  }

  @After
  public void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  public void canListEmptyStorage() throws Exception {
    assertThat(storage.list()).isEmpty();
  }

  @Test
  public void canListPersistedUpdate() throws Exception {
    storage.persist(REF_UPDATE);
    assertContainsExactly(storage, REF_UPDATE);
  }

  @Test
  public void canDeletePersistedUpdate() throws Exception {
    storage.persist(REF_UPDATE);
    storage.delete(REF_UPDATE);
    assertThat(storage.list()).isEmpty();
  }

  @Test
  public void instancesOfTheSameStorageHaveTheSameElements() throws Exception {
    ReplicationTasksStorage persistedView = new ReplicationTasksStorage(storageSite);

    assertThat(storage.list()).isEmpty();
    assertThat(persistedView.list()).isEmpty();

    storage.persist(REF_UPDATE);
    assertContainsExactly(storage, REF_UPDATE);
    assertContainsExactly(persistedView, REF_UPDATE);

    storage.delete(REF_UPDATE);
    assertThat(storage.list()).isEmpty();
    assertThat(persistedView.list()).isEmpty();
  }

  @Test
  public void sameRefUpdatePersistedTwiceIsStoredOnce() throws Exception {
    String key = storage.persist(REF_UPDATE);
    String secondKey = storage.persist(REF_UPDATE);
    assertEquals(key, secondKey);
    assertContainsExactly(storage, REF_UPDATE);
  }

  @Test
  public void canPersistDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);

    String keyA = storage.persist(REF_UPDATE);
    String keyB = storage.persist(updateB);
    assertThat(storage.list()).hasSize(2);
    assertNotEquals(keyA, keyB);
  }

  @Test
  public void canDeleteDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    storage.persist(REF_UPDATE);
    storage.persist(updateB);

    storage.delete(REF_UPDATE);
    assertContainsExactly(storage, updateB);

    storage.delete(updateB);
    assertThat(storage.list()).isEmpty();
  }

  @Test
  public void differentUrisPersistedTwiceIsStoredOnce() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);

    storage.persist(REF_UPDATE);
    storage.persist(updateB);
    storage.persist(REF_UPDATE);
    storage.persist(updateB);
    assertThat(storage.list()).hasSize(2);
  }

  @Test
  public void canPersistMulipleRefsForSameUri() throws Exception {
    ReplicateRefUpdate refA = new ReplicateRefUpdate(PROJECT, "refA", URISH, REMOTE);
    ReplicateRefUpdate refB = new ReplicateRefUpdate(PROJECT, "refB", URISH, REMOTE);

    String keyA = storage.persist(refA);
    String keyB = storage.persist(refB);
    assertThat(storage.list()).hasSize(2);
    assertNotEquals(keyA, keyB);
  }

  @Test
  public void canDeleteMulipleRefsForSameUri() throws Exception {
    ReplicateRefUpdate refA = new ReplicateRefUpdate(PROJECT, "refA", URISH, REMOTE);
    ReplicateRefUpdate refB = new ReplicateRefUpdate(PROJECT, "refB", URISH, REMOTE);
    storage.persist(refA);
    storage.persist(refB);

    storage.delete(refA);
    assertContainsExactly(storage, refB);

    storage.delete(refB);
    assertThat(storage.list()).isEmpty();
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalDeleteNonPersistedIsGraceful() throws Exception {
    storage.delete(REF_UPDATE);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalDoubleDeleteIsGraceful() throws Exception {
    storage.persist(REF_UPDATE);
    storage.delete(REF_UPDATE);

    storage.delete(REF_UPDATE);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalDoubleDeleteDifferentUriIsGraceful() throws Exception {
    ReplicateRefUpdate updateB =
        new ReplicateRefUpdate(
            PROJECT,
            REF,
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    storage.persist(REF_UPDATE);
    storage.persist(updateB);
    storage.delete(REF_UPDATE);
    storage.delete(updateB);

    storage.delete(REF_UPDATE);
    storage.delete(updateB);
    assertThat(storage.list()).isEmpty();
  }

  public static void assertContainsExactly(
      ReplicationTasksStorage tasksStorage, ReplicateRefUpdate update) {
    assertTrue(equals(tasksStorage.list().get(0), update));
  }

  private static boolean equals(ReplicateRefUpdate one, ReplicateRefUpdate two) {
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
