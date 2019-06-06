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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
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
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig, ReplicationDestinations {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long RELOAD_DELAY = 120;
  private static final long RELOAD_INTERVAL = 60;

  private volatile ReplicationFileBasedConfig currentConfig;

  // Use Provider<> instead of injecting the ReplicationQueue because of
  // circular dependency with ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;
  private final ScheduledExecutorService autoReloadExecutor;
  private ScheduledFuture<?> autoReloadRunnable;
  private final AutoReloadRunnable reloadRunner;

  @Inject
  public AutoReloadConfigDecorator(
      Provider<ReplicationQueue> replicationQueue,
      @PluginName String pluginName,
      WorkQueue workQueue,
      ReplicationFileBasedConfig replicationConfig,
      AutoReloadRunnable reloadRunner,
      EventBus eventBus) {
    this.currentConfig = replicationConfig;
    this.replicationQueue = replicationQueue;
    this.autoReloadExecutor = workQueue.createQueue(1, pluginName + "_auto-reload-config");
    this.reloadRunner = reloadRunner;
    eventBus.register(this);
  }

  @Override
  public synchronized List<Destination> getAll(FilterType filterType) {
    return currentConfig.getAll(filterType);
  }

  @Override
  public synchronized Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    return currentConfig.getURIs(remoteName, projectName, filterType);
  }

  @VisibleForTesting
  public void reload() {
    reloadRunner.reload();
  }

  @Override
  public synchronized boolean isReplicateAllOnPluginStart() {
    return currentConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public synchronized boolean isDefaultForceUpdate() {
    return currentConfig.isDefaultForceUpdate();
  }

  @Override
  public synchronized int getMaxRefsToLog() {
    return currentConfig.getMaxRefsToLog();
  }

  @Override
  public synchronized boolean isEmpty() {
    return currentConfig.isEmpty();
  }

  @Override
  public Path getEventsDirectory() {
    return currentConfig.getEventsDirectory();
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
    stopAutoReload();
    return currentConfig.shutdown();
  }

  private synchronized void stopAutoReload() {
    if (autoReloadRunnable != null) {
      autoReloadRunnable.cancel(false);
      autoReloadRunnable = null;
    }
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    currentConfig.startup(workQueue);
    autoReloadRunnable =
        autoReloadExecutor.scheduleAtFixedRate(
            reloadRunner, RELOAD_DELAY, RELOAD_INTERVAL, TimeUnit.SECONDS);
  }

  @Override
  public String getVersion() {
    return currentConfig.getVersion();
  }

  @Subscribe
  public void onReload(ReplicationFileBasedConfig newConfig) {
    try {
      replicationQueue.get().stop();
      currentConfig = newConfig;
      logger.atInfo().log(
          "Configuration reloaded: %d destinations", newConfig.getAll(FilterType.ALL).size());
    } finally {
      replicationQueue.get().start();
    }
  }

  @Override
  public synchronized int getSshConnectionTimeout() {
    return currentConfig.getSshConnectionTimeout();
  }

  @Override
  public synchronized int getSshCommandTimeout() {
    return currentConfig.getSshCommandTimeout();
  }
}
