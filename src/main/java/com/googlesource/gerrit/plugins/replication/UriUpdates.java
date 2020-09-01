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

import com.google.gerrit.entities.Project;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.transport.URIish;

/** A data container for updates to a single URI */
public interface UriUpdates {
  Project.NameKey getProjectNameKey();

  URIish getURI();

  String getRemoteName();

  Set<String> getRefs();

  default List<ReplicationTasksStorage.ReplicateRefUpdate> getReplicateRefUpdates() {
    return getRefs().stream()
        .map(
            (ref) ->
                new ReplicationTasksStorage.ReplicateRefUpdate(
                    getProjectNameKey().get(), ref, getURI(), getRemoteName()))
        .collect(Collectors.toList());
  }
}
