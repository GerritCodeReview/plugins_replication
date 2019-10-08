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

/** Remote configuration for a replication endpoint */
public interface RemoteConfiguration {

  /**
   * Time to wait before scheduling a remote replication operation. Setting to 0 effectively
   * disables the delay.
   *
   * @return the delay value in seconds
   */
  int getDelay();

  /**
   * Time to wait before rescheduling a remote replication operation, which might have failed the
   * first time round. Setting to 0 effectively disables the delay.
   *
   * @return the delay value in seconds
   */
  int getRescheduleDelay();

  /**
   * Time to wait before retrying a failed remote replication operation, Setting to 0 effectively
   * disables the delay.
   *
   * @return the delay value in seconds
   */
  int getRetryDelay();

  /**
   * List of the remote endpoint addresses used for replication.
   *
   * @return list of remote URL strings
   */
  ImmutableList<String> getUrls();

  /**
   * List of alternative remote endpoint addresses, used for admin operations, such as repository
   * creation
   *
   * @return list of remote URL strings
   */
  ImmutableList<String> getAdminUrls();

  /**
   * List of repositories that should be replicated
   *
   * @return list of project strings
   */
  ImmutableList<String> getProjects();

  /**
   * List of groups that should be used to access the repositories.
   *
   * @return list of group strings
   */
  ImmutableList<String> getAuthGroupNames();

  /**
   * Influence how the name of the remote repository should be computed.
   *
   * @return a string representing a remote style name
   */
  String getRemoteNameStyle();

  /**
   * If true, permissions-only projects and the refs/meta/config branch will also be replicated
   *
   * @return a string representing a remote style name
   */
  boolean replicatePermissions();

  /**
   * the JGIT remote configuration representing the replication for this endpoint
   *
   * @return The remote config {@link RemoteConfig}
   */
  RemoteConfig getRemoteConfig();

  /**
   * Number of times to retry a replication operation
   *
   * @return the number of retries
   */
  int getMaxRetries();

  /**
   * returns true, when the configuration is individual to one single project rather than many
   * projects
   *
   * @return whether this configuration is for one single project
   */
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

  /**
   * the time duration after which the replication for a project should be considered “slow”
   *
   * @return the slow latency threshold
   */
  int getSlowLatencyThreshold();
}
