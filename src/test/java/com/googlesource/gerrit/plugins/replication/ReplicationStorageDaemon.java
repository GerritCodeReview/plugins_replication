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

import static java.util.stream.Collectors.toList;

import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class can be extended by any ReplicationStorage*IT class and provides common setup and
 * helper methods.
 */
public class ReplicationStorageDaemon extends ReplicationDaemon {
  protected static final int TEST_TASK_FINISH_SECONDS = 1;
  protected static final int TEST_REPLICATION_MAX_RETRIES = 1;
  protected static final Duration TEST_TASK_FINISH_TIMEOUT =
      Duration.ofSeconds(TEST_TASK_FINISH_SECONDS);
  protected static final Duration MAX_RETRY_WITH_TOLERANCE_TIMEOUT =
      Duration.ofSeconds(
          (TEST_REPLICATION_DELAY_SECONDS + TEST_REPLICATION_RETRY_MINUTES * 60)
                  * TEST_REPLICATION_MAX_RETRIES
              + 10);
  protected ReplicationTasksStorage tasksStorage;
  protected DestinationsCollection destinationCollection;
  protected ReplicationConfig replicationConfig;

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
    destinationCollection = plugin.getSysInjector().getInstance(DestinationsCollection.class);
    replicationConfig = plugin.getSysInjector().getInstance(ReplicationConfig.class);
  }

  protected List<ReplicationTasksStorage.ReplicateRefUpdate> listWaitingReplicationTasks(
      String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage
        .streamWaiting()
        .filter(task -> refmaskPattern.matcher(task.refs().toArray()[0].toString()).matches())
        .collect(toList());
  }

  protected void deleteWaitingReplicationTasks(String refRegex) {
    Path refUpdates = replicationConfig.getEventsDirectory().resolve("ref-updates");
    Path waitingUpdates = refUpdates.resolve("waiting");
    for (ReplicationTasksStorage.ReplicateRefUpdate r : listWaitingReplicationTasks(refRegex)) {
      try {
        Files.deleteIfExists(waitingUpdates.resolve(r.sha1()));
      } catch (IOException e) {
        throw new RuntimeException("Couldn't delete waiting task", e);
      }
    }
  }

  protected List<ReplicationTasksStorage.ReplicateRefUpdate> listWaiting() {
    return tasksStorage.streamWaiting().collect(Collectors.toList());
  }

  protected List<ReplicationTasksStorage.ReplicateRefUpdate> listRunning() {
    return tasksStorage.streamRunning().collect(Collectors.toList());
  }
}
