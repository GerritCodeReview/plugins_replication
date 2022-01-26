// Copyright (C) 2022 The Android Open Source Project
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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.util.Optional;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class HasProjectTask {
  interface Factory {
    HasProjectTask create(Project.NameKey project);
  }

  private final RemoteConfig config;
  private final ReplicationDestinations destinations;
  private final DynamicItem<AdminApiFactory> adminApiFactory;
  private final Project.NameKey project;

  @Inject
  HasProjectTask(
      RemoteConfig config,
      ReplicationDestinations destinations,
      DynamicItem<AdminApiFactory> adminApiFactory,
      @Assisted Project.NameKey project) {
    this.config = config;
    this.destinations = destinations;
    this.adminApiFactory = adminApiFactory;
    this.project = project;
  }

  public boolean hasProject() {
    return destinations.getURIs(Optional.of(config.getName()), project, FilterType.PROJECT_CREATION)
        .values().stream()
        .map(u -> hasProject(u, project))
        .reduce(true, (a, b) -> a && b);
  }

  private boolean hasProject(URIish replicateURI, Project.NameKey projectName) {
    Optional<AdminApi> adminApi = adminApiFactory.get().create(replicateURI);
    return adminApi.isPresent() && adminApi.get().hasProject(projectName);
  }
}
