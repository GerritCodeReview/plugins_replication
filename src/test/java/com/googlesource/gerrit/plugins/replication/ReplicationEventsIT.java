// Copyright (C) 2021 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;

import com.google.common.base.Objects;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.EventGsonProvider;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationFailedEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationScheduledEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationSucceededEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ReplicationScheduledEvent;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

@UseLocalDisk
@Sandboxed
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.TestReplicationModule")
public class ReplicationEventsIT extends ReplicationDaemon {
  private static final Duration TEST_POST_EVENT_TIMEOUT = Duration.ofSeconds(1);

  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  @Inject private DynamicItem<EventDispatcher> eventDispatcher;
  private TestDispatcher testDispatcher;
  private Gson eventGson;

  @Before
  public void setup() throws Exception {
    initConfig();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    setUpTestPlugin();
    testDispatcher = new TestDispatcher();
    eventDispatcher.set(testDispatcher, eventDispatcher.getPluginName());
    eventGson = new EventGsonProvider().get();
  }

  @Test
  public void replicateNewChangeSendsEvents() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    String sourceRef = pushResult.getPatchSet().refName();
    String metaRef = pushResult.getChange().notes().getRefName();
    BranchNameKey changeBranch = BranchNameKey.create(project, sourceRef);
    BranchNameKey metaBranch = BranchNameKey.create(project, metaRef);

    assertThat(testDispatcher.getEvents(changeBranch, ReplicationScheduledEvent.class)).hasSize(1);
    assertThat(testDispatcher.getEvents(metaBranch, ReplicationScheduledEvent.class)).hasSize(1);

    isPushCompleted(targetProject, sourceRef, TEST_PUSH_TIMEOUT);
    isPushCompleted(targetProject, metaRef, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), sourceRef);
    assertThat(testDispatcher.getEvents(RefReplicatedEvent.class).size()).isEqualTo(2);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), metaRef);
    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), sourceRef);
    assertThat(testDispatcher.getEvents(RefReplicationDoneEvent.class).size()).isEqualTo(2);
  }

  @Test
  public void replicateNewBranchSendsEvents() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newBranch = "refs/heads/mybranch";
    BranchNameKey branchName = BranchNameKey.create(project, newBranch);
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    assertThat(testDispatcher.getEvents(branchName, ReplicationScheduledEvent.class)).hasSize(1);

    isPushCompleted(targetProject, newBranch, TEST_PUSH_TIMEOUT);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicatedEvent.class), newBranch);
    assertThat(testDispatcher.getEvents(RefReplicatedEvent.class).size()).isEqualTo(1);

    waitForRefEvent(() -> testDispatcher.getEvents(RefReplicationDoneEvent.class), newBranch);
    assertThat(testDispatcher.getEvents(RefReplicationDoneEvent.class).size()).isEqualTo(1);
  }

  @Test
  public void shouldEmitProjectDeletionEventsForOneRemote() throws Exception {
    String projectName = project.get();
    setReplicationTarget("replica", project.get());

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    List<ProjectDeletionReplicationScheduledEvent> scheduledEvents =
        testDispatcher.getEvents(project, ProjectDeletionReplicationScheduledEvent.class);
    assertThat(scheduledEvents).hasSize(1);

    assertThatAnyMatch(
        scheduledEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class), 1);

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class),
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class), 1);

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class),
        e -> project.equals(e.getProjectNameKey()));
  }

  @Test
  public void shouldEmitProjectDeletionEventsForMultipleRemotesWhenSucceeding() throws Exception {
    String projectName = project.get();
    setReplicationTarget("replica1", projectName);
    setReplicationTarget("replica2", projectName);

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    List<ProjectDeletionReplicationScheduledEvent> scheduledEvents =
        testDispatcher.getEvents(project, ProjectDeletionReplicationScheduledEvent.class);
    assertThat(scheduledEvents).hasSize(2);

    assertThatAnyMatch(
        scheduledEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica1.git"));
    assertThatAnyMatch(
        scheduledEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica2.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class), 2);

    List<ProjectDeletionReplicationSucceededEvent> successEvents =
        testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class);

    assertThatAnyMatch(
        successEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica1.git"));
    assertThatAnyMatch(
        successEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica2.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class), 1);

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class),
        e -> project.equals(e.getProjectNameKey()));
  }

  @Test
  public void shouldEmitProjectDeletionEventsForMultipleRemotesWhenFailing() throws Exception {
    String projectName = project.get();
    setReplicationTarget("replica1", projectName);

    setReplicationDestination(
        "not-existing-replica", "not-existing-replica", Optional.of(projectName));
    setProjectDeletionReplication("not-existing-replica", true);

    reloadConfig();

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(projectDeletedEvent(projectName));
    }

    List<ProjectDeletionReplicationScheduledEvent> scheduledEvents =
        testDispatcher.getEvents(project, ProjectDeletionReplicationScheduledEvent.class);
    assertThat(scheduledEvents).hasSize(2);

    assertThatAnyMatch(
        scheduledEvents,
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica1.git"));
    assertThatAnyMatch(
        scheduledEvents,
        e ->
            project.equals(e.getProjectNameKey())
                && e.getTargetUri().endsWith("not-existing-replica.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class), 1);

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationFailedEvent.class), 1);

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationSucceededEvent.class),
        e -> project.equals(e.getProjectNameKey()) && e.getTargetUri().endsWith("replica1.git"));

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationFailedEvent.class),
        e ->
            project.equals(e.getProjectNameKey())
                && e.getTargetUri().endsWith("not-existing-replica.git"));

    waitForProjectEvent(
        () -> testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class), 1);

    assertThatAnyMatch(
        testDispatcher.getEvents(project, ProjectDeletionReplicationDoneEvent.class),
        e -> project.equals(e.getProjectNameKey()));
  }

  @Test
  public void shouldSerializeObjectsHavingProjectDeletionReplicationScheduledEventAsField()
      throws Exception {
    EventWrapper origEvent =
        new EventWrapper(
            new ProjectDeletionReplicationScheduledEvent(
                project.get(), new URIish(String.format("git://someHost/%s.git", project.get()))));

    EventWrapper gotEvent = eventGson.fromJson(eventGson.toJson(origEvent), origEvent.getClass());

    assertThat(origEvent).isEqualTo(gotEvent);
  }

  @Test
  public void shouldSerializeObjectsHavingProjectDeletionReplicationSucceededEventAsField()
      throws Exception {
    EventWrapper origEvent =
        new EventWrapper(
            new ProjectDeletionReplicationSucceededEvent(
                project.get(), new URIish(String.format("git://someHost/%s.git", project.get()))));

    EventWrapper gotEvent = eventGson.fromJson(eventGson.toJson(origEvent), origEvent.getClass());

    assertThat(origEvent).isEqualTo(gotEvent);
  }

  @Test
  public void shouldSerializeObjectsHavingProjectDeletionReplicationFailedEventAsField()
      throws Exception {
    EventWrapper origEvent =
        new EventWrapper(
            new ProjectDeletionReplicationFailedEvent(
                project.get(), new URIish(String.format("git://someHost/%s.git", project.get()))));

    EventWrapper gotEvent = eventGson.fromJson(eventGson.toJson(origEvent), origEvent.getClass());

    assertThat(origEvent).isEqualTo(gotEvent);
  }

  @Test
  public void shouldSerializeObjectsHavingProjectDeletionReplicationDoneEventAsField() {
    EventWrapper origEvent =
        new EventWrapper(new ProjectDeletionReplicationDoneEvent(project.get()));

    EventWrapper gotEvent = eventGson.fromJson(eventGson.toJson(origEvent), origEvent.getClass());

    assertThat(origEvent).isEqualTo(gotEvent);
  }

  @Test
  public void shouldSerializeRefReplicatedEvent() throws URISyntaxException {
    RefReplicatedEvent origEvent =
        new RefReplicatedEvent(
            project.get(),
            "refs/heads/master",
            new URIish(String.format("git://someHost/%s.git", project.get())),
            ReplicationState.RefPushResult.SUCCEEDED,
            RemoteRefUpdate.Status.OK);

    assertThat(origEvent)
        .isEqualTo(eventGson.fromJson(eventGson.toJson(origEvent), RefReplicatedEvent.class));
  }

  @Test
  public void shouldSerializeReplicationScheduledEvent() throws URISyntaxException {
    ReplicationScheduledEvent origEvent =
        new ReplicationScheduledEvent(
            project.get(),
            "refs/heads/master",
            new URIish(String.format("git://someHost/%s.git", project.get())));

    assertTrue(
        equals(
            origEvent,
            eventGson.fromJson(eventGson.toJson(origEvent), ReplicationScheduledEvent.class)));
  }

  private <T extends RefEvent> void waitForRefEvent(Supplier<List<T>> events, String refName)
      throws InterruptedException {
    WaitUtil.waitUntil(
        () -> events.get().stream().filter(e -> refName.equals(e.getRefName())).count() == 1,
        TEST_POST_EVENT_TIMEOUT);
  }

  private <T extends ProjectEvent> void waitForProjectEvent(Supplier<List<T>> events, int count)
      throws InterruptedException {
    WaitUtil.waitUntil(() -> events.get().size() == count, TEST_POST_EVENT_TIMEOUT);
  }

  private Project.NameKey setReplicationTarget(String replica, String ofProject) throws Exception {
    Project.NameKey replicaProject = createTestProject(String.format("%s%s", ofProject, replica));
    setReplicationDestination(replica, replica, Optional.of(ofProject));
    setProjectDeletionReplication(replica, true);
    return replicaProject;
  }

  private <T extends ProjectEvent> void assertThatAnyMatch(List<T> events, Predicate<T> p) {
    assertThat(events.stream().anyMatch(p)).isTrue();
  }

  private static class EventWrapper {
    private final Event event;

    public EventWrapper(Event event) {
      this.event = event;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof EventWrapper)) {
        return false;
      }
      EventWrapper eventWrapper = (EventWrapper) o;
      return Objects.equal(event, eventWrapper.event);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(event);
    }
  }

  @SuppressWarnings("deprecation")
  private boolean equals(ReplicationScheduledEvent scheduledEvent, Object other) {
    if (!(other instanceof ReplicationScheduledEvent)) {
      return false;
    }
    ReplicationScheduledEvent event = (ReplicationScheduledEvent) other;
    if (!Objects.equal(event.project, scheduledEvent.project)) {
      return false;
    }
    if (!Objects.equal(event.ref, scheduledEvent.ref)) {
      return false;
    }
    if (!Objects.equal(event.targetUri, scheduledEvent.targetUri)) {
      return false;
    }
    if (!Objects.equal(event.status, scheduledEvent.status)) {
      return false;
    }
    return Objects.equal(event.targetNode, scheduledEvent.targetNode);
  }
}
