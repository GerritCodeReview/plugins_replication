// Copyright (C) 2018 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.util.Optional;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class CreateProjectTask {
  interface Factory {
    CreateProjectTask create(Project.NameKey project, String head);
  }

  private final RemoteConfig config;
  private final ReplicationDestinations replicationDestinations;
  private final DynamicItem<AdminApiFactory> adminApiFactory;
  private final Project.NameKey project;
  private final String head;

  @Inject
  CreateProjectTask(
      RemoteConfig config,
      ReplicationDestinations replicationDestinations,
      DynamicItem<AdminApiFactory> adminApiFactory,
      @Assisted Project.NameKey project,
      @Assisted String head) {
    this.config = config;
    this.replicationDestinations = replicationDestinations;
    this.adminApiFactory = adminApiFactory;
    this.project = project;
    this.head = head;
  }

  public boolean create() {
    return replicationDestinations
        .getURIs(Optional.of(config.getName()), project, FilterType.PROJECT_CREATION).values()
        .stream()
        .map(u -> createProject(u, project, head))
        .reduce(true, (a, b) -> a && b);
  }

  private boolean createProject(URIish replicateURI, Project.NameKey projectName, String head) {
    Optional<AdminApi> adminApi = adminApiFactory.get().create(replicateURI);
    if (adminApi.isPresent() && adminApi.get().createProject(projectName, head)) {
      return true;
    }

    repLog.warn("Cannot create new project {} on remote site {}.", projectName, replicateURI);
    return false;
  }
}
