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
   * @param projectName name of the project
   * @param filterType type of filter criteria for selecting projects
   * @return the multi-map of destinations and the associated replication URIs
   */
  Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType);

  /**
   * Returns current replication configuration of whether to replicate or not all the projects when
   * the plugin starts.
   *
   * @return true if replication at plugin start, false otherwise.
   */
  boolean isReplicateAllOnPluginStart();

  /**
   * Returns the default behaviour of the replication plugin when pushing to remote replication
   * ends. Even though the property name has the 'update' suffix, it actually refers to Git push
   * operation and not to a Git update.
   *
   * @return true if forced push is the default, false otherwise.
   */
  boolean isDefaultForceUpdate();

  /**
   * Returns the maximum number of ref-specs to log into the replication_log whenever a push
   * operation is completed against a replication end.
   *
   * @return maximum number of refs to log, zero if unlimited.
   */
  int getMaxRefsToLog();

  /**
   * Configured location where the replication events are stored on the filesystem for being resumed
   * and kept across restarts.
   *
   * @return path to store persisted events.
   */
  Path getEventsDirectory();

  /**
   * Current logical version string of the current configuration loaded in memory, depending on the
   * actual implementation of the configuration on the persistent storage.
   *
   * @return current logical version number.
   */
  String getVersion();
}
