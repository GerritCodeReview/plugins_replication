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

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.util.IdGenerator;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

public class DeleteProjectTask implements Runnable {
  interface Factory {
    DeleteProjectTask create(URIish replicateURI, Project.NameKey project);
  }

  private final AdminApiFactory adminApiFactory;
  private final int id;
  private final URIish replicateURI;
  private final Project.NameKey project;

  @Inject
  DeleteProjectTask(
      AdminApiFactory adminApiFactory,
      IdGenerator ig,
      @Assisted URIish replicateURI,
      @Assisted Project.NameKey project) {
    this.adminApiFactory = adminApiFactory;
    this.id = ig.next();
    this.replicateURI = replicateURI;
    this.project = project;
  }

  @Override
  public void run() {
    Optional<AdminApi> adminApi = adminApiFactory.create(replicateURI);
    if (adminApi.isPresent()) {
      adminApi.get().deleteProject(project);
      return;
    }

    repLog.warn("Cannot delete project {} on remote site {}.", project, replicateURI);
  }

  @Override
  public String toString() {
    return String.format(
        "[%s] delete-project %s at %s", HexFormat.fromInt(id), project.get(), replicateURI);
  }
}
