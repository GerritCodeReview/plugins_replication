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
import static com.googlesource.gerrit.plugins.replication.ReplicationConfigImpl.replaceName;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ConfigParser.ReplicationConfigInvalidException;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class DestinationsCollection implements ReplicationDestinations {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Destination.Factory destinationFactory;
  private final Provider<ReplicationQueue> replicationQueue;
  private volatile List<Destination> destinations;
  private boolean shuttingDown;

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
      ReplicationConfig replicationConfig,
      ConfigParser configParser,
      EventBus eventBus)
      throws ReplicationConfigInvalidException {
    this.destinationFactory = destinationFactory;
    this.replicationQueue = replicationQueue;
    this.destinations =
        allDestinations(
            destinationFactory, configParser.parseRemotes(replicationConfig.getConfig()));
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
      if (filterType != FilterType.PROJECT_DELETION && !config.wouldPushProject(projectName)) {
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
          repLog.atWarning().log("adminURL '%s' is invalid: %s", url, e.getMessage());
          continue;
        }

        if (!isGerrit(uri) && !isGerritHttp(uri)) {
          String path =
              replaceName(uri.getPath(), projectName.get(), config.isSingleProjectMatch());
          if (path == null) {
            repLog.atWarning().log("adminURL %s does not contain ${name}", uri);
            continue;
          }

          uri = uri.setPath(path);
          if (!isSSH(uri)) {
            repLog.atWarning().log(
                "adminURL '%s' is invalid: only SSH and HTTP are supported", uri);
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
  public List<Destination> getAll(FilterType filterType) {
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
  public List<Destination> getDestinations(URIish uri, Project.NameKey project, Set<String> refs) {
    List<Destination> dests = new ArrayList<>();
    for (Destination dest : getAll(FilterType.ALL)) {
      for (String ref : refs) {
        if (dest.wouldPush(uri, project, ref)) {
          dests.add(dest);
        }
      }
    }
    return dests;
  }

  @Override
  public boolean isEmpty() {
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
    synchronized (this) {
      shuttingDown = true;
    }

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
        Thread.sleep(destination.getReplicationDelayMilliseconds());
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
  public synchronized void onReload(List<RemoteConfiguration> remoteConfigurations) {
    if (shuttingDown) {
      logger.atWarning().log("Shutting down: configuration reload ignored");
      return;
    }

    try {
      replicationQueue.get().stop();
      destinations = allDestinations(destinationFactory, remoteConfigurations);
      logger.atInfo().log("Configuration reloaded: %d destinations", getAll(FilterType.ALL).size());
    } finally {
      replicationQueue.get().start();
    }
  }

  private List<Destination> allDestinations(
      Destination.Factory destinationFactory, List<RemoteConfiguration> remoteConfigurations) {

    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfiguration c : remoteConfigurations) {
      if (c instanceof DestinationConfiguration) {
        dest.add(destinationFactory.create((DestinationConfiguration) c));
      }
    }
    return dest.build();
  }
}
