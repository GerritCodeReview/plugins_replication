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
import com.google.gerrit.reviewdb.client.Project;
import java.util.List;
import org.eclipse.jgit.transport.URIish;

/** A replication endpoint */
public interface ReplicationEndpoint {

  /**
   * Whether the given project should be replicated
   *
   * @param project the project to check against
   * @return boolean value
   */
  boolean wouldReplicateProject(Project.NameKey project);

  /**
   * list of URIs configured for project that match a given regular expression
   *
   * @param project the project to check against
   * @param urlMatch a regular expression
   * @return List of {@link URIish}
   */
  List<URIish> getURIs(Project.NameKey project, String urlMatch);

  /**
   * The remote configuration name this endpoint is associated to
   *
   * @return The configuration name
   */
  String getRemoteConfigName();

  /**
   * List of the remote endpoint addresses used for replication.
   *
   * @return list of remote URL strings
   */
  ImmutableList<String> getUrls();

  /**
   * List of groups that should be used to access the repositories.
   *
   * @return list of group strings
   */
  ImmutableList<String> getAuthGroupNames();

  /**
   * List of repositories that should be replicated
   *
   * @return list of project strings
   */
  ImmutableList<String> getProjects();

  /**
   * List of alternative remote endpoint addresses, used for admin operations, such as repository
   * creation
   *
   * @return list of remote URL strings
   */
  ImmutableList<String> getAdminUrls();

  /**
   * Whether the given ref name should be replicated
   *
   * @param ref The ref name
   * @return boolean value
   */
  boolean wouldReplicateRef(String ref);
}
