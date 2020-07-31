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

import com.google.auto.value.AutoValue;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.UriUpdates;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;

@AutoValue
public abstract class TestUriUpdates implements UriUpdates {
  public static TestUriUpdates create(ReplicateRefUpdate update) throws URISyntaxException {
    return create(
        Project.nameKey(update.project),
        new URIish(update.uri),
        update.remote,
        Collections.singleton(update.ref));
  }

  public static TestUriUpdates create(
      Project.NameKey project, URIish uri, String remote, Set<String> refs) {
    return new AutoValue_TestUriUpdates(project, uri, remote, refs);
  }

  @Override
  public abstract Project.NameKey getProjectNameKey();

  @Override
  public abstract URIish getURI();

  @Override
  public abstract String getRemoteName();

  @Override
  public abstract Set<String> getRefs();
}
