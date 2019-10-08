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

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.eclipse.jgit.transport.RemoteConfig;

public interface RemoteConfiguration {

  int getDelay();

  int getRescheduleDelay();

  int getRetryDelay();

  int getDrainQueueAttempts();

  int getPoolThreads();

  int getLockErrorMaxRetries();

  ImmutableList<String> getUrls();

  ImmutableList<String> getAdminUrls();

  ImmutableList<String> getProjects();

  ImmutableList<String> getAuthGroupNames();

  String getRemoteNameStyle();

  boolean replicatePermissions();

  boolean createMissingRepos();

  boolean replicateProjectDeletions();

  boolean replicateHiddenProjects();

  RemoteConfig getRemoteConfig();

  int getMaxRetries();

  default boolean isSingleProjectMatch() {
    List<String> projects = getProjects();
    boolean ret = (projects.size() == 1);
    if (ret) {
      String projectMatch = projects.get(0);
      if (ReplicationFilter.getPatternType(projectMatch)
          != ReplicationFilter.PatternType.EXACT_MATCH) {
        // projectMatch is either regular expression, or wild-card.
        //
        // Even though they might refer to a single project now, they need not
        // after new projects have been created. Hence, we do not treat them as
        // matching a single project.
        ret = false;
      }
    }
    return ret;
  }

  int getSlowLatencyThreshold();
}
