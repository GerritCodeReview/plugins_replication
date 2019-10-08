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

public interface ReplicationEndpoint {

  boolean wouldReplicateProject(Project.NameKey project);

  List<URIish> getURIs(Project.NameKey project, String urlMatch);

  String getRemoteConfigName();

  ImmutableList<String> getUrls();

  ImmutableList<String> getAuthGroupNames();

  ImmutableList<String> getProjects();

  ImmutableList<String> getAdminUrls();

  boolean wouldReplicateRef(String ref);

  void schedule(Project.NameKey project, String ref, URIish uri, ReplicationState state);

  void schedule(
      Project.NameKey project, String ref, URIish uri, ReplicationState state, boolean now);
}
