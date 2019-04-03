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

import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

/** Collection of Git repositories destinations for replication. */
public class DestinationsCollection {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ReplicationConfig replicationConfig;
  private final Destination.Factory destinationFactory;
  private List<Destination> allDestinations;

  @Inject
  DestinationsCollection(
      ReplicationConfig replicationConfig, Destination.Factory destinationFactory) {
    this.replicationConfig = replicationConfig;
    this.destinationFactory = destinationFactory;
  }

  /**
   * Get all destinations matching the specified type.
   *
   * @param filterType type of destination.
   * @return list of destinations matching the specified filter type.
   */
  public List<Destination> getAll(FilterType filterType) {
    if (replicationConfig.reloadIfNeeded()) {
      try {
        load();
      } catch (ConfigInvalidException e) {
        logger.atWarning().withCause(e).log("Unable to load new destinations");
      }
    }

    Predicate<? super Destination> filter;
    switch (filterType) {
      case PROJECT_CREATION:
        filter = dest -> dest.isCreateMissingRepos();
        break;
      case PROJECT_DELETION:
        filter = dest -> dest.isReplicateProjectDeletions();
        break;
      case ALL:
      default:
        filter = dest -> true;
        break;
    }
    return allDestinations.stream().filter(Objects::nonNull).filter(filter).collect(toList());
  }

  /**
   * Start replication on all destinations.
   *
   * @param workQueue replication queue.
   * @throws ConfigInvalidException
   */
  public void startup(WorkQueue workQueue) throws ConfigInvalidException {
    load();
    for (Destination destination : allDestinations) {
      destination.start(workQueue);
    }
  }

  /**
   * Shutdown replication on all destinations.
   *
   * @return number of pending replication events that have been discarded.
   */
  public int shutdown() {
    int discarded = 0;
    for (Destination destination : allDestinations) {
      discarded += destination.shutdown();
    }
    return discarded;
  }

  private void load() throws ConfigInvalidException {
    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfig c : allRemotes()) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // If destination for push is not set assume equal to source.
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        c.addPushRefSpec(
            new RefSpec()
                .setSourceDestination("refs/*", "refs/*")
                .setForceUpdate(replicationConfig.isDefaultForceUpdate()));
      }

      Destination destination =
          destinationFactory.create(new DestinationConfiguration(c, replicationConfig.getConfig()));

      if (!destination.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format("remote.%s.url \"%s\" lacks ${name} placeholder", c.getName(), u));
          }
        }
      }

      dest.add(destination);
    }

    this.allDestinations = dest.build();
    logger.atInfo().log("%d replication destinations loaded", allDestinations.size());
  }

  private List<RemoteConfig> allRemotes() throws ConfigInvalidException {
    Config config = replicationConfig.getConfig();
    Set<String> names = config.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(config, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(String.format("remote %s has invalid URL", name));
      }
    }
    return result;
  }
}
