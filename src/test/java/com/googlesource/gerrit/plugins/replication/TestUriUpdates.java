// Copyright (C) 2020 The Android Open Source Project
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

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.net.URISyntaxException;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;

public class TestUriUpdates implements UriUpdates {
  private final Project.NameKey project;
  private final URIish uri;
  private final String remote;
  private final Set<ImmutableSet<String>> refs;

  public TestUriUpdates(ReplicateRefUpdate update) throws URISyntaxException {
    project = Project.nameKey(update.project());
    uri = new URIish(update.uri());
    remote = update.remote();
    refs = Set.of(update.refs());
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return project;
  }

  @Override
  public URIish getURI() {
    return uri;
  }

  @Override
  public String getRemoteName() {
    return remote;
  }

  @Override
  public Set<ImmutableSet<String>> getRefs() {
    return refs;
  }
}
