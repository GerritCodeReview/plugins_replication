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

import com.google.common.collect.Multimap;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig.FilterType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;

/** Git destinations currently active for replication. */
public interface ReplicationDestinations {

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
   * List of currently active replication destinations.
   *
   * @param filterType type project filtering
   * @return the list of active destinations
   */
  List<Destination> getAll(FilterType filterType);

  /**
   * Return the active replication destinations for a uri/project/refs triplet.
   *
   * @param uriish uri of the destinations
   * @param project name of the project
   * @param refs ref names
   * @return the list of active destinations
   */
  List<Destination> getDestinations(URIish uriish, Project.NameKey project, Set<String> refs);

  /** Returns true if there are no destinations, false otherwise. */
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
