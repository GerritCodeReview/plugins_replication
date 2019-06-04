// Copyright (C) 2019 The Android Open Source Project
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

import com.google.gerrit.server.git.WorkQueue;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.util.List;

/** Git destinations currently active for replication. */
public interface ReplicationDestinations {

  /**
   * List of currently active replication destinations.
   *
   * @param filterType type project filtering
   * @return the list of active destinations
   */
  List<Destination> getAll(FilterType filterType);

  /** @return true if there are no destinations, false otherwise. */
  boolean isEmpty();

  /**
   * Start replicating to all destinations.
   *
   * @param workQueue execution queue for scheduling the replication events.
   */
  void startup(WorkQueue workQueue);

  /**
   * Stop the replication to all destinations.
   *
   * @return number of events cancelled during shutdown.
   */
  int shutdown();
}
