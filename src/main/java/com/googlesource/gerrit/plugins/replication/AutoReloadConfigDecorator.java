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

import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoReloadConfigDecorator
    implements ReplicationConfig, ReplicationDestinations, ReplicationConfigListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long RELOAD_DELAY = 120;
  private static final long RELOAD_INTERVAL = 60;

  private ReplicationFileBasedConfig replicationConfig;

  // Use Provider<> instead of injecting the ReplicationQueue because of
  // circular dependency with
  // ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;
  private final ScheduledExecutorService autoReloadExecutor;
  private AutoReloadRunnable reloadRunner;
  private ScheduledFuture<?> scheduledReloadRunner;

  @Inject
  public AutoReloadConfigDecorator(
      Provider<ReplicationQueue> replicationQueue,
      @PluginName String pluginName,
      WorkQueue workQueue,
      ReplicationFileBasedConfig replicationConfig,
      AutoReloadRunnable reloadRunner) {
    this.replicationConfig = replicationConfig;
    this.replicationQueue = replicationQueue;
    this.autoReloadExecutor = workQueue.createQueue(1, pluginName + "_auto-reload-config");
    this.reloadRunner = reloadRunner;
  }

  @Override
  public synchronized List<Destination> getAll(FilterType filterType) {
    return replicationConfig.getAll(filterType);
  }

  @Override
  public synchronized Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    return replicationConfig.getURIs(remoteName, projectName, filterType);
  }

  @Override
  public synchronized boolean isReplicateAllOnPluginStart() {
    return replicationConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public synchronized boolean isDefaultForceUpdate() {
    return replicationConfig.isDefaultForceUpdate();
  }

  @Override
  public synchronized int getMaxRefsToLog() {
    return replicationConfig.getMaxRefsToLog();
  }

  @Override
  public synchronized boolean isEmpty() {
    return replicationConfig.isEmpty();
  }

  @Override
  public Path getEventsDirectory() {
    return replicationConfig.getEventsDirectory();
  }

  @Override
  public synchronized int shutdown() {
    scheduledReloadRunner.cancel(true);
    return replicationConfig.shutdown();
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    replicationConfig.startup(workQueue);
    scheduledReloadRunner =
        autoReloadExecutor.scheduleAtFixedRate(
            reloadRunner, RELOAD_DELAY, RELOAD_INTERVAL, TimeUnit.SECONDS);
  }

  @Override
  public String getVersion() {
    return replicationConfig.getVersion();
  }

  @Override
  public void beforeReload(ReplicationFileBasedConfig oldConfig) {
    replicationQueue.get().stop();
  }

  @Override
  public void afterReload(ReplicationFileBasedConfig newConfig) throws ConfigInvalidException {
    try {
      replicationConfig = newConfig;
      logger.atInfo().log(
          "Configuration reloaded: %d destinations", newConfig.getAll(FilterType.ALL).size());
    } finally {
      replicationQueue.get().start();
    }
  }
}
