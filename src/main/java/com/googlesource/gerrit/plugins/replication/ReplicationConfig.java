// Copyright (C) 2013 The Android Open Source Project
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

import static java.util.stream.Collectors.toList;

import com.google.gerrit.server.git.WorkQueue;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

public interface ReplicationConfig {

  enum FilterType {
    PROJECT_CREATION,
    PROJECT_DELETION,
    ALL;

    public Predicate<? super Destination> getDestiationPredicate() {
      switch (this) {
        case PROJECT_CREATION:
          return dest -> dest.isCreateMissingRepos();
        case PROJECT_DELETION:
          return dest -> dest.isReplicateProjectDeletions();
        case ALL:
        default:
          return dest -> true;
      }
    }
  }

  default List<Destination> getDestinationsForRemote(String remote, FilterType filterType) {
    return streamDestinations(filterType)
        .filter(dest -> remote.equals(dest.getRemoteConfigName()))
        .collect(toList());
  }

  List<Destination> getDestinations(FilterType filterType);

  default Stream<Destination> streamDestinations(FilterType filterType) {
    return getDestinations(filterType).stream();
  }

  boolean isReplicateAllOnPluginStart();

  boolean isDefaultForceUpdate();

  boolean isEmpty();

  Path getEventsDirectory();

  int shutdown();

  void startup(WorkQueue workQueue);

  int getSshConnectionTimeout();

  int getSshCommandTimeout();
}
