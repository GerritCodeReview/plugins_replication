// Copyright (C) 2026 The Android Open Source Project
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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig.FilterType;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionState;
import com.googlesource.gerrit.plugins.replication.events.dispatcher.EventDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationQueueTest {
  private static final String REMOTE = "remote";
  private static final String REF = "refs/heads/master";
  private static final Duration REPLAY_TIMEOUT = Duration.ofSeconds(10);

  private Project.NameKey project;
  private URIish uri;
  private ReplicateRefUpdate update;
  private List<ReplicateRefUpdate> waitingTasks;
  private Map<ReplicateRefUpdate, String> taskNamesByReplicateRefUpdate;
  private ScheduledThreadPoolExecutor defaultQueue;
  private WorkQueue workQueue;
  private Destination destination;
  private ReplicationQueue replicationQueue;

  @Before
  public void setUp() throws Exception {
    project = Project.nameKey("project");
    uri = new URIish("git://host/project.git");
    update = ReplicateRefUpdate.create(project.get(), Set.of(REF), uri, REMOTE);

    waitingTasks = new ArrayList<>();
    taskNamesByReplicateRefUpdate = new HashMap<>();

    destination = mock(Destination.class);
    when(destination.isPushEnabled()).thenReturn(true);
    when(destination.getRemoteConfigName()).thenReturn(REMOTE);
    when(destination.getTaskNamesByReplicateRefUpdate()).thenReturn(taskNamesByReplicateRefUpdate);

    ReplicationDestinations destinations = mock(ReplicationDestinations.class);
    when(destinations.getAll(FilterType.ALL)).thenReturn(List.of(destination));
    when(destinations.getDestinations(any(), any(), any())).thenReturn(List.of(destination));

    ReplicationTasksStorage tasksStorage = mock(ReplicationTasksStorage.class);
    when(tasksStorage.streamWaiting()).thenAnswer(_ -> List.copyOf(waitingTasks).stream());

    defaultQueue = new ScheduledThreadPoolExecutor(1);
    workQueue = mock(WorkQueue.class);
    when(workQueue.getDefaultQueue()).thenReturn(defaultQueue);

    replicationQueue =
        new ReplicationQueue(
            mock(ReplicationConfig.class),
            workQueue,
            () -> destinations,
            mock(EventDispatcher.class),
            mock(ReplicationStateListeners.class),
            tasksStorage,
            mock(ProjectDeletionState.Factory.class));
  }

  @After
  public void tearDown() {
    defaultQueue.shutdownNow();
  }

  @Test
  public void distributorFiresTaskNotPendingOnThisNode() throws Exception {
    start();
    waitingTasks.add(update);
    runDistributor();

    verify(destination).scheduleFromStorage(eq(project), eq(update.refs()), eq(uri), any());
  }

  @Test
  public void startupFiresTaskPendingOnThisNode() throws Exception {
    waitingTasks.add(update);
    taskNamesByReplicateRefUpdate.put(update, "pending push task");
    start();

    verify(destination, never()).getTaskNamesByReplicateRefUpdate();
    verify(destination).scheduleFromStorage(eq(project), eq(update.refs()), eq(uri), any());
  }

  private void start() throws Exception {
    replicationQueue.start();
    awaitReplayed();
  }

  private void runDistributor() throws Exception {
    replicationQueue.new Distributor(workQueue).run();
    awaitReplayed();
  }

  private void awaitReplayed() throws InterruptedException {
    long deadline = System.nanoTime() + REPLAY_TIMEOUT.toNanos();
    while (replicationQueue.isReplaying() && System.nanoTime() < deadline) {
      MILLISECONDS.sleep(5);
    }
    assertThat(replicationQueue.isReplaying()).isFalse();
  }
}
