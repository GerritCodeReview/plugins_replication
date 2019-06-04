// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.collect.Multimap;
import com.google.gerrit.reviewdb.client.Project;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

/** Configuration of all the replication end points. */
public interface ReplicationConfig {

  /** Filter for accessing replication projects. */
  enum FilterType {
    PROJECT_CREATION,
    PROJECT_DELETION,
    ALL
  }

  /**
   * Return all the URIs associated to a project and a filter criteria.
   *
   * @param remoteName name of the replication end or empty if selecting all ends.
   * @param projectName name of the project or empty if getting all projects
   * @param filterType type of filter criteria for selecting projects
   * @return the multi-map of destinations and the associated replication URIs
   */
  Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType);

  /**
   * Replicate all projects at the plugin start.
   *
   * @return true if the plugin is configured to replication all projects at start.
   */
  boolean isReplicateAllOnPluginStart();

  /**
   * Forced push by default to the replication ends.
   *
   * @return true if forced push is the default, false otherwise.
   */
  boolean isDefaultForceUpdate();

  /**
   * Maximum number of refs to trace on replication_log.
   *
   * @return the maximum number of refs to log.
   */
  int getMaxRefsToLog();

  /**
   * Location where the replication events are persisted.
   *
   * @return path of the replication persistence directory.
   */
  Path getEventsDirectory();
}
