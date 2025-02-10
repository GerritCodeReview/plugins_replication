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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.truth.IterableSubject;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationTasksStorageTest {
  protected static final String PROJECT = "myProject";
  protected static final String REF = "myRef";
  protected static final String REF_2 = "myRef2";
  protected static final String REMOTE = "myDest";
  protected static final URIish URISH = getUrish("http://example.com/" + PROJECT + ".git");
  protected static final ReplicateRefUpdate REF_UPDATE =
      ReplicateRefUpdate.create(PROJECT, Set.of(REF), URISH, REMOTE);
  protected static final ReplicateRefUpdate STORED_REF_UPDATE =
      ReplicateRefUpdate.create(REF_UPDATE, REF_UPDATE.sha1());
  protected static final ReplicateRefUpdate REFS_UPDATE =
      ReplicateRefUpdate.create(PROJECT, Set.of(REF, REF_2), URISH, REMOTE);
  protected static final ReplicateRefUpdate STORED_REFS_UPDATE =
      ReplicateRefUpdate.create(REFS_UPDATE, REFS_UPDATE.sha1());

  protected ReplicationTasksStorage storage;
  protected FileSystem fileSystem;
  protected Path storageSite;
  protected UriUpdates uriUpdates;

  @Before
  public void setUp() throws Exception {
    fileSystem = Jimfs.newFileSystem(Configuration.unix());
    storageSite = fileSystem.getPath("replication_site");
    storage = new ReplicationTasksStorage(storageSite);
    uriUpdates = new TestUriUpdates(REF_UPDATE);
  }

  @After
  public void tearDown() throws Exception {
    fileSystem.close();
  }

  @Test
  public void canListEmptyStorage() throws Exception {
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  @Test
  public void canListWaitingUpdate() throws Exception {
    storage.create(REF_UPDATE);
    assertThatStream(storage.streamWaiting()).containsExactly(STORED_REF_UPDATE);
  }

  @Test
  public void canCheckIfUpdateIsWaiting() {
    storage.create(REF_UPDATE);
    assertTrue(storage.isWaiting(uriUpdates));

    storage.start(uriUpdates);
    assertFalse(storage.isWaiting(uriUpdates));
  }

  @Test
  public void canStartWaitingUpdate() throws Exception {
    storage.create(REF_UPDATE);
    assertThat(storage.start(uriUpdates)).containsExactly(REF_UPDATE.refs());
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertFalse(storage.isWaiting(uriUpdates));
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);
  }

  @Test
  public void canStartWaitingUpdateWithMultipleRefs() throws Exception {
    TestUriUpdates updates = new TestUriUpdates(REFS_UPDATE);
    storage.create(REFS_UPDATE);
    assertThat(storage.start(updates)).containsExactly(REFS_UPDATE.refs());
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertFalse(storage.isWaiting(updates));
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REFS_UPDATE);
  }

  @Test
  public void canFinishRunningUpdate() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);
    storage.finish(uriUpdates);
    assertNoIncompleteTasks(storage);
  }

  @Test
  public void instancesOfTheSameStorageHaveTheSameElements() throws Exception {
    ReplicationTasksStorage persistedView = new ReplicationTasksStorage(storageSite);

    assertThatStream(storage.streamWaiting()).isEmpty();
    assertThatStream(persistedView.streamWaiting()).isEmpty();

    storage.create(REF_UPDATE);
    assertThatStream(storage.streamWaiting()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(persistedView.streamWaiting()).containsExactly(STORED_REF_UPDATE);

    storage.start(uriUpdates);
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertThatStream(persistedView.streamWaiting()).isEmpty();
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(persistedView.streamRunning()).containsExactly(STORED_REF_UPDATE);

    storage.finish(uriUpdates);
    assertThatStream(storage.streamRunning()).isEmpty();
    assertThatStream(persistedView.streamRunning()).isEmpty();
  }

  @Test
  public void sameRefUpdateCreatedTwiceIsStoredOnce() throws Exception {
    String key = storage.create(REF_UPDATE);
    String secondKey = storage.create(REF_UPDATE);
    assertEquals(key, secondKey);
    assertThatStream(storage.streamWaiting()).containsExactly(STORED_REF_UPDATE);
  }

  @Test
  public void canCreateDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);

    String keyA = storage.create(REF_UPDATE);
    String keyB = storage.create(updateB);
    assertThatStream(storage.streamWaiting()).hasSize(2);
    assertTrue(storage.isWaiting(uriUpdates));
    assertTrue(storage.isWaiting(new TestUriUpdates(updateB)));
    assertNotEquals(keyA, keyB);
  }

  @Test
  public void canStoreAndReadUrisWithEncodedChars() throws Exception {
    String urlBase = "https://git.server.example.com";
    String url = urlBase + "/${name}.git";
    URIish template = new URIish(url);
    String strangeValidName =
        "project/with/a/strange/name key=a-value=1, --option1 \"OPTION_VALUE_1\" --option-2"
            + " <option_VALUE-2> --option-without-value";
    Project.NameKey project = Project.nameKey(strangeValidName);
    URIish expanded = Destination.getURI(template, project, "slash", false);
    ReplicateRefUpdate update =
        ReplicateRefUpdate.create(strangeValidName, Set.of(REF), expanded, REMOTE);
    storage.create(update);

    assertThat(new URIish(update.uri())).isEqualTo(expanded);
    assertThatStream(storage.streamWaiting()).hasSize(1);
    for (ReplicateRefUpdate rru : storage.streamWaiting().collect(Collectors.toList())) {
      assertThat(new URIish(rru.uri())).isEqualTo(expanded);
    }
  }

  @Test
  public void canStartDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    UriUpdates uriUpdatesB = new TestUriUpdates(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);

    ReplicateRefUpdate storedUpdateB = ReplicateRefUpdate.create(updateB, updateB.sha1());
    storage.start(uriUpdates);
    assertThatStream(storage.streamWaiting()).containsExactly(storedUpdateB);
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);

    storage.start(uriUpdatesB);
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE, storedUpdateB);
  }

  @Test
  public void canFinishDifferentUris() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    UriUpdates uriUpdatesB = new TestUriUpdates(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);
    storage.start(uriUpdates);
    storage.start(uriUpdatesB);

    storage.finish(uriUpdates);
    assertThatStream(storage.streamRunning())
        .containsExactly(ReplicateRefUpdate.create(updateB, updateB.sha1()));

    storage.finish(uriUpdatesB);
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  @Test
  public void differentUrisCreatedTwiceIsStoredOnce() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);

    storage.create(REF_UPDATE);
    storage.create(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);
    assertThatStream(storage.streamWaiting()).hasSize(2);
    assertTrue(storage.isWaiting(uriUpdates));
    assertTrue(storage.isWaiting(new TestUriUpdates(updateB)));
  }

  @Test
  public void canCreateMulipleRefsForSameUri() throws Exception {
    ReplicateRefUpdate refA = ReplicateRefUpdate.create(PROJECT, Set.of("refA"), URISH, REMOTE);
    ReplicateRefUpdate refB = ReplicateRefUpdate.create(PROJECT, Set.of("refB"), URISH, REMOTE);

    String keyA = storage.create(refA);
    String keyB = storage.create(refB);
    assertThatStream(storage.streamWaiting()).hasSize(2);
    assertNotEquals(keyA, keyB);
    assertTrue(storage.isWaiting(new TestUriUpdates(refA)));
    assertTrue(storage.isWaiting(new TestUriUpdates(refB)));
  }

  @Test
  public void canFinishMulipleRefsForSameUri() throws Exception {
    ReplicateRefUpdate refUpdateA =
        ReplicateRefUpdate.create(PROJECT, Set.of("refA"), URISH, REMOTE);
    ReplicateRefUpdate refUpdateB =
        ReplicateRefUpdate.create(PROJECT, Set.of("refB"), URISH, REMOTE);
    UriUpdates uriUpdatesA = new TestUriUpdates(refUpdateA);
    UriUpdates uriUpdatesB = new TestUriUpdates(refUpdateB);
    storage.create(refUpdateA);
    storage.create(refUpdateB);
    storage.start(uriUpdatesA);
    storage.start(uriUpdatesB);

    storage.finish(uriUpdatesA);
    assertThatStream(storage.streamRunning())
        .containsExactly(ReplicateRefUpdate.create(refUpdateB, refUpdateB.sha1()));

    storage.finish(uriUpdatesB);
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  @Test
  public void canResetUpdate() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);

    storage.reset(uriUpdates);
    assertThatStream(storage.streamWaiting()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  @Test
  public void canCompleteResetUpdate() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);
    storage.reset(uriUpdates);

    storage.start(uriUpdates);
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertFalse(storage.isWaiting(uriUpdates));

    storage.finish(uriUpdates);
    assertNoIncompleteTasks(storage);
  }

  @Test
  public void canRecoverEmpty() throws Exception {
    storage.recoverAll();
    assertNoIncompleteTasks(storage);
  }

  @Test
  public void canRecoverUpdate() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);

    storage.recoverAll();
    assertThatStream(storage.streamWaiting()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(storage.streamRunning()).isEmpty();
    assertTrue(storage.isWaiting(uriUpdates));
  }

  @Test
  public void canCompleteRecoveredUpdate() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);
    storage.recoverAll();

    storage.start(uriUpdates);
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertFalse(storage.isWaiting(uriUpdates));

    storage.finish(uriUpdates);
    assertNoIncompleteTasks(storage);
  }

  @Test
  public void canRecoverMultipleUpdates() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    UriUpdates uriUpdatesB = new TestUriUpdates(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);
    storage.start(uriUpdates);
    storage.start(uriUpdatesB);

    storage.recoverAll();
    assertThatStream(storage.streamWaiting())
        .containsExactly(STORED_REF_UPDATE, ReplicateRefUpdate.create(updateB, updateB.sha1()));
  }

  @Test
  public void canCompleteMultipleRecoveredUpdates() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    UriUpdates uriUpdatesB = new TestUriUpdates(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);
    storage.start(uriUpdates);
    storage.start(uriUpdatesB);
    storage.recoverAll();

    ReplicateRefUpdate storedUpdateB = ReplicateRefUpdate.create(updateB, updateB.sha1());
    storage.start(uriUpdates);
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE);
    assertThatStream(storage.streamWaiting()).containsExactly(storedUpdateB);

    storage.start(uriUpdatesB);
    assertThatStream(storage.streamRunning()).containsExactly(STORED_REF_UPDATE, storedUpdateB);
    assertThatStream(storage.streamWaiting()).isEmpty();

    storage.finish(uriUpdates);
    storage.finish(uriUpdatesB);
    assertNoIncompleteTasks(storage);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalFinishUncreatedIsGraceful() throws Exception {
    storage.finish(uriUpdates);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalDoubleFinishIsGraceful() throws Exception {
    storage.create(REF_UPDATE);
    storage.start(uriUpdates);
    storage.finish(uriUpdates);

    storage.finish(uriUpdates);
  }

  @Test(expected = Test.None.class /* no exception expected */)
  public void illegalDoubleFinishDifferentUriIsGraceful() throws Exception {
    ReplicateRefUpdate updateB =
        ReplicateRefUpdate.create(
            PROJECT,
            Set.of(REF),
            getUrish("ssh://example.com/" + PROJECT + ".git"), // uses ssh not http
            REMOTE);
    UriUpdates uriUpdatesB = new TestUriUpdates(updateB);
    storage.create(REF_UPDATE);
    storage.create(updateB);
    storage.start(uriUpdates);
    storage.start(uriUpdatesB);
    storage.finish(uriUpdates);
    storage.finish(uriUpdatesB);

    storage.finish(uriUpdates);
    storage.finish(uriUpdatesB);
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  protected static void assertNoIncompleteTasks(ReplicationTasksStorage storage) {
    assertThatStream(storage.streamWaiting()).isEmpty();
    assertThatStream(storage.streamRunning()).isEmpty();
  }

  protected static IterableSubject assertThatStream(Stream<?> stream) {
    return assertThat(stream.collect(Collectors.toList()));
  }

  public static URIish getUrish(String uri) {
    try {
      return new URIish(uri);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Cannot instantiate URIish object", e);
    }
  }
}
