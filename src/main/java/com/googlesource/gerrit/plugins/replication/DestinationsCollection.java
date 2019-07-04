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

import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isGerrit;
import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isGerritHttp;
import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isSSH;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.Destination.Factory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class DestinationsCollection implements ReplicationDestinations, ReplicationConfigValidator {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Factory destinationFactory;
  private final Provider<ReplicationQueue> replicationQueue;
  private volatile List<Destination> destinations;
  private volatile boolean shuttingDown;

  public static class EventQueueNotEmptyException extends Exception {
    private static final long serialVersionUID = 1L;

    public EventQueueNotEmptyException(String errorMessage) {
      super(errorMessage);
    }
  }

  @Inject
  public DestinationsCollection(
      Destination.Factory destinationFactory,
      Provider<ReplicationQueue> replicationQueue,
      ReplicationFileBasedConfig replicationConfig,
      EventBus eventBus)
      throws ConfigInvalidException {
    this.destinationFactory = destinationFactory;
    this.replicationQueue = replicationQueue;
    this.destinations = allDestinations(destinationFactory, validateConfig(replicationConfig));
    eventBus.register(this);
  }

  @Override
  public Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    if (getAll(filterType).isEmpty()) {
      return ImmutableMultimap.of();
    }

    SetMultimap<Destination, URIish> uris = HashMultimap.create();
    for (Destination config : getAll(filterType)) {
      if (!config.wouldPushProject(projectName)) {
        continue;
      }

      if (remoteName.isPresent() && !config.getRemoteConfigName().equals(remoteName.get())) {
        continue;
      }

      boolean adminURLUsed = false;

      for (String url : config.getAdminUrls()) {
        if (Strings.isNullOrEmpty(url)) {
          continue;
        }

        URIish uri;
        try {
          uri = new URIish(url);
        } catch (URISyntaxException e) {
          repLog.warn("adminURL '{}' is invalid: {}", url, e.getMessage());
          continue;
        }

        if (!isGerrit(uri) && !isGerritHttp(uri)) {
          String path =
              replaceName(uri.getPath(), projectName.get(), config.isSingleProjectMatch());
          if (path == null) {
            repLog.warn("adminURL {} does not contain ${name}", uri);
            continue;
          }

          uri = uri.setPath(path);
          if (!isSSH(uri)) {
            repLog.warn("adminURL '{}' is invalid: only SSH and HTTP are supported", uri);
            continue;
          }
        }
        uris.put(config, uri);
        adminURLUsed = true;
      }

      if (!adminURLUsed) {
        for (URIish uri : config.getURIs(projectName, "*")) {
          uris.put(config, uri);
        }
      }
    }
    return uris;
  }

  @Override
  public synchronized List<Destination> getAll(FilterType filterType) {
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

  @Override
  public synchronized boolean isEmpty() {
    return destinations.isEmpty();
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    shuttingDown = false;
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }

  /* shutdown() cannot be set as a synchronized method because
   * it may need to wait for pending events to complete;
   * e.g. when enabling the drain of replication events before
   * shutdown.
   *
   * As a rule of thumb for synchronized methods, because they
   * implicitly define a critical section and associated lock,
   * they should never hold waiting for another resource, otherwise
   * the risk of deadlock is very high.
   *
   * See more background about deadlocks, what they are and how to
   * prevent them at: https://en.wikipedia.org/wiki/Deadlock
   */
  @Override
  public int shutdown() {
    shuttingDown = true;

    int discarded = 0;
    for (Destination cfg : destinations) {
      try {
        drainReplicationEvents(cfg);
      } catch (EventQueueNotEmptyException e) {
        logger.atWarning().log("Event queue not empty: %s", e.getMessage());
      } finally {
        discarded += cfg.shutdown();
      }
    }
    return discarded;
  }

  void drainReplicationEvents(Destination destination) throws EventQueueNotEmptyException {
    int drainQueueAttempts = destination.getDrainQueueAttempts();
    if (drainQueueAttempts == 0) {
      return;
    }
    int pending = destination.getQueueInfo().pending.size();
    int inFlight = destination.getQueueInfo().inFlight.size();
    while ((inFlight > 0 || pending > 0) && drainQueueAttempts > 0) {
      try {
        logger.atInfo().log(
            "Draining replication events, postpone shutdown. Events left: inFlight %d, pending %d",
            inFlight, pending);
        Thread.sleep(destination.getReplicationDelaySeconds());
      } catch (InterruptedException ie) {
        logger.atWarning().withCause(ie).log(
            "Wait for replication events to drain has been interrupted");
      }
      pending = destination.getQueueInfo().pending.size();
      inFlight = destination.getQueueInfo().inFlight.size();
      drainQueueAttempts--;
    }
    if (pending > 0 || inFlight > 0) {
      throw new EventQueueNotEmptyException(
          String.format("Pending: %d - InFlight: %d", pending, inFlight));
    }
  }

  @Subscribe
  public synchronized void onReload(List<DestinationConfiguration> destinationConfigurations) {
    if (shuttingDown) {
      logger.atWarning().log("Shutting down: configuration reload ignored");
      return;
    }

    try {
      replicationQueue.get().stop();
      destinations = allDestinations(destinationFactory, destinationConfigurations);
      logger.atInfo().log("Configuration reloaded: %d destinations", getAll(FilterType.ALL).size());
    } finally {
      replicationQueue.get().start();
    }
  }

  @Override
  public List<DestinationConfiguration> validateConfig(ReplicationFileBasedConfig configuration)
      throws ConfigInvalidException {
    if (!configuration.getConfig().getFile().exists()) {
      logger.atWarning().log(
          "Config file %s does not exist; not replicating", configuration.getConfig().getFile());
      return Collections.emptyList();
    }
    if (configuration.getConfig().getFile().length() == 0) {
      logger.atInfo().log(
          "Config file %s is empty; not replicating", configuration.getConfig().getFile());
      return Collections.emptyList();
    }

    try {
      configuration.getConfig().load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(
          String.format(
              "Config file %s is invalid: %s", configuration.getConfig().getFile(), e.getMessage()),
          e);
    } catch (IOException e) {
      throw new ConfigInvalidException(
          String.format("Cannot read %s: %s", configuration.getConfig().getFile(), e.getMessage()),
          e);
    }

    boolean defaultForceUpdate =
        configuration.getConfig().getBoolean("gerrit", "defaultForceUpdate", false);

    ImmutableList.Builder<DestinationConfiguration> confs = ImmutableList.builder();
    for (RemoteConfig c : allRemotes(configuration.getConfig())) {
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
                .setForceUpdate(defaultForceUpdate));
      }

      DestinationConfiguration destinationConfiguration =
          new DestinationConfiguration(c, configuration.getConfig());

      if (!destinationConfiguration.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format(
                    "remote.%s.url \"%s\" lacks ${name} placeholder in %s",
                    c.getName(), u, configuration.getConfig().getFile()));
          }
        }
      }

      confs.add(destinationConfiguration);
    }

    return confs.build();
  }

  private List<Destination> allDestinations(
      Destination.Factory destinationFactory,
      List<DestinationConfiguration> destinationConfigurations) {

    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (DestinationConfiguration c : destinationConfigurations) {
      dest.add(destinationFactory.create(c));
    }
    return dest.build();
  }

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg) throws ConfigInvalidException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(
            String.format("remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }
    return result;
  }

  static String replaceName(String in, String name, boolean keyIsOptional) {
    String key = "${name}";
    int n = in.indexOf(key);
    if (0 <= n) {
      return in.substring(0, n) + name + in.substring(n + key.length());
    }
    if (keyIsOptional) {
      return in;
    }
    return null;
  }
}
