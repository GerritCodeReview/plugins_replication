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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.util.logging.NamedFluentLogger;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Continuously replicates every project to every configured remote in the background.
 *
 * <p>Disabled unless {@code replication.replicateAllInBackground} is set to true.
 */
@Singleton
class ReplicateAllRunner {
  private static final NamedFluentLogger repLog = ReplicationQueue.repLog;
  private final ProjectCache projectCache;
  private final ReplicationQueue replicationQueue;
  private final Provider<ReplicationDestinations> destinations;
  private final ReplicationConfig replicationConfig;
  private volatile boolean stopped;

  @Inject
  ReplicateAllRunner(
      ProjectCache projectCache,
      ReplicationQueue replicationQueue,
      Provider<ReplicationDestinations> destinations,
      ReplicationConfig replicationConfig) {
    this.projectCache = projectCache;
    this.replicationQueue = replicationQueue;
    this.destinations = destinations;
    this.replicationConfig = replicationConfig;
  }

  void start() {
    if (!replicationConfig.isReplicateAllInBackground()) {
      return;
    }
    stopped = false;
    for (Destination dest : destinations.get().getAll(ReplicationConfig.FilterType.ALL)) {
      replicateAll(dest);
    }
  }

  void stop() {
    stopped = true;
  }

  private void replicateAll(Destination dest) {
    ScheduledExecutorService executor = dest.getExecutor();
    if (stopped || executor == null || executor.isShutdown()) {
      return;
    }
    String remoteName = dest.getRemoteConfigName();
    repLog.atInfo().log("Background replicate all to %s started", remoteName);
    Iterator<Project.NameKey> iter = projectCache.all().iterator();
    new ChainedScheduler<>(
        executor,
        iter,
        new ChainedScheduler.Runner<>() {
          @Override
          public void run(Project.NameKey project) {
            if (stopped) {
              return;
            }
            ReplicationState state = new ReplicationState(PushResultProcessing.NO_OP);
            replicationQueue.scheduleFullSync(project, null, Set.of(remoteName), state, true);
            state.markAllPushTasksScheduled();
          }

          @Override
          public void onDone() {
            if (stopped || executor.isShutdown()) {
              return;
            }
            executor.execute(
                new Runnable() {
                  @Override
                  public void run() {
                    replicateAll(dest);
                  }

                  @Override
                  public String toString() {
                    return "Background replicate all to " + remoteName;
                  }
                });
          }

          @Override
          public String toString(Project.NameKey project) {
            return "Background replicate project " + project.get() + " to " + remoteName;
          }
        });
  }
}
