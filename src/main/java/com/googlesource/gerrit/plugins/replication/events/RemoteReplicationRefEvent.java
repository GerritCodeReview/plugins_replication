// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.events;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.RefEvent;
import org.eclipse.jgit.transport.URIish;

public class RemoteReplicationRefEvent extends RefEvent {

  final String project;
  final String ref;
  final String status;
  final String targetUri;

  public RemoteReplicationRefEvent(
      String type, String project, String ref, URIish targetUri, @Nullable String status) {
    super(type);
    this.project = project;
    this.ref = ref;
    this.status = status;
    this.targetUri = targetUri.toASCIIString();
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @Override
  public String getRefName() {
    return ref;
  }

  @Nullable
  public String getStatus() {
    return status;
  }

  public String getTargetUri() {
    return targetUri;
  }
}
