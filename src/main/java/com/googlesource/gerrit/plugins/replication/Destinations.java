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

public class Destinations {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ReplicationConfig replicationConfig;
  private final DestinationFactory destinationFactory;
  private List<Destination> destinations;

  @Inject
  public Destinations(ReplicationConfig replicationConfig, DestinationFactory destinationFactory) {
    this.replicationConfig = replicationConfig;
    this.destinationFactory = destinationFactory;
  }

  private void load() throws ConfigInvalidException {
    this.destinations = allDestinations();
  }

  /*
   * (non-Javadoc)
   * @see
   * com.googlesource.gerrit.plugins.replication.ReplicationConfig#getDestinations
   * (com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType)
   */
  public List<Destination> getDestinations(FilterType filterType) {
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
    return destinations.stream().filter(Objects::nonNull).filter(filter).collect(toList());
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isEmpty()
   */
  public boolean isEmpty() {
    return destinations.isEmpty();
  }

  List<Destination> allDestinations() throws ConfigInvalidException {

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

    List<Destination> dests = dest.build();
    logger.atInfo().log("%d replication destinations loaded", dests.size());
    return dests;
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

  public int shutdown() {
    int discarded = 0;
    for (Destination cfg : destinations) {
      discarded += cfg.shutdown();
    }
    return discarded;
  }

  public void startup(WorkQueue workQueue) throws ConfigInvalidException {
    load();
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }
}
