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
import com.google.inject.Inject;
import com.google.inject.Provider;
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
public class DestinationsCollection implements ReplicationConfigListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Destination.Factory destinationFactory;
  // Use Provider<> instead of injecting the ReplicationQueue because of circular dependency with
  // ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;

  private List<Destination> allDestinations;
  private boolean bootstrapped;
  private boolean started;
  private ReplicationConfig replicationConfig;

  @Inject
  DestinationsCollection(
      Destination.Factory destinationFactory, Provider<ReplicationQueue> replicationQueue) {
    this.destinationFactory = destinationFactory;
    this.replicationQueue = replicationQueue;
  }

  /**
   * Get all destinations matching the specified type.
   *
   * @param filterType type of destination.
   * @return list of destinations matching the specified filter type.
   */
  public List<Destination> getAll(FilterType filterType) {
    replicationConfig.getConfig();

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
   * @throws ConfigInvalidException
   */
  public void startup() throws ConfigInvalidException {
    if (started) {
      return;
    }

    allDestinations = loadDestinations(replicationConfig.getConfig());

    for (Destination destination : allDestinations) {
      destination.start(replicationQueue.get());
    }

    bootstrapped = true;
  }

  /**
   * Shutdown replication on all destinations.
   *
   * @return number of pending replication events that have been discarded.
   */
  public int shutdown() {
    if (!started) {
      return 0;
    }

    int discarded = 0;
    for (Destination destination : allDestinations) {
      discarded += destination.shutdown();
    }
    return discarded;
  }

  @Override
  public void beforeLoad() {
    shutdown();
  }

  @Override
  public void afterLoad(ReplicationConfig replicationConfig) throws ConfigInvalidException {
    ReplicationConfig oldConfig = this.replicationConfig;
    try {
      this.replicationConfig = replicationConfig;
      if (bootstrapped) {
        startup();
      }
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Unable to load destinations");
      this.replicationConfig = oldConfig;
    }
  }

  private List<RemoteConfig> allRemotes(Config newConfig) throws ConfigInvalidException {
    Set<String> names = newConfig.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(newConfig, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(String.format("remote %s has invalid URL", name));
      }
    }
    return result;
  }

  private List<Destination> loadDestinations(Config config) throws ConfigInvalidException {
    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfig c : allRemotes(config)) {
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

    ImmutableList<Destination> destinations = dest.build();
    logger.atInfo().log("%d replication destinations loaded", destinations.size());

    return destinations;
  }
}
