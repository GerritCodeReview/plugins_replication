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
import com.google.gerrit.common.FileUtil;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final long RELOAD_DELAY = 120;
  private static final long RELOAD_INTERVAL = 60;

  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;
  private long lastFailedConfigTs;

  private final SitePaths site;
  private final Destination.Factory destinationFactory;
  private final Path pluginDataDir;
  // Use Provider<> instead of injecting the ReplicationQueue because of circular dependency with
  // ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;
  private final ScheduledExecutorService autoReloadExecutor;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site,
      Destination.Factory destinationFactory,
      Provider<ReplicationQueue> replicationQueue,
      @PluginData Path pluginDataDir,
      @PluginName String pluginName,
      WorkQueue workQueue)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.pluginDataDir = pluginDataDir;
    this.currentConfig = loadConfig();
    this.currentConfigTs = getLastModified(currentConfig);
    this.replicationQueue = replicationQueue;
    this.autoReloadExecutor = workQueue.createQueue(1, pluginName + "_auto-reload-config");
  }

  private static long getLastModified(ReplicationFileBasedConfig cfg) {
    return FileUtil.lastModified(cfg.getCfgPath());
  }

  private ReplicationFileBasedConfig loadConfig() throws ConfigInvalidException, IOException {
    return new ReplicationFileBasedConfig(site, destinationFactory, pluginDataDir);
  }

  private synchronized boolean isAutoReload() {
    return currentConfig.getConfig().getBoolean("gerrit", "autoReload", false);
  }

  @Override
  public synchronized List<Destination> getDestinations(FilterType filterType) {
    return currentConfig.getDestinations(filterType);
  }

  @Override
  public synchronized Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    return currentConfig.getURIs(remoteName, projectName, filterType);
  }

  private synchronized void reloadIfNeeded() {
    if (isAutoReload()) {
      ReplicationQueue queue = replicationQueue.get();

      long lastModified = getLastModified(currentConfig);
      try {
        if (lastModified > currentConfigTs
            && lastModified > lastFailedConfigTs
            && queue.isRunning()
            && !queue.isReplaying()) {
          queue.stop();
          currentConfig = loadConfig();
          currentConfigTs = lastModified;
          lastFailedConfigTs = 0;
          logger.atInfo().log(
              "Configuration reloaded: %d destinations",
              currentConfig.getDestinations(FilterType.ALL).size());
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log(
            "Cannot reload replication configuration: keeping existing settings");
        lastFailedConfigTs = lastModified;
        return;
      } finally {
        queue.start();
      }
    }
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

  @Override
  public synchronized int shutdown() {
    return currentConfig.shutdown();
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    currentConfig.startup(workQueue);
    autoReloadExecutor.scheduleAtFixedRate(
        this::reloadIfNeeded, RELOAD_DELAY, RELOAD_INTERVAL, TimeUnit.SECONDS);
  }
}
