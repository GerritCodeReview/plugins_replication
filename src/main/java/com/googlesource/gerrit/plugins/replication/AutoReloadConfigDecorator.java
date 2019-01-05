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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.FileUtil;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;
  private long lastFailedConfigTs;

  private final SitePaths site;
  private final DestinationFactory destinationFactory;
  private final Path pluginDataDir;
  // Use Provider<> instead of injecting the ReplicationQueue because of circular dependency with
  // ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site,
      DestinationFactory destinationFactory,
      Provider<ReplicationQueue> replicationQueue,
      @PluginData Path pluginDataDir)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.pluginDataDir = pluginDataDir;
    this.currentConfig = loadConfig();
    this.currentConfigTs = getLastModified(currentConfig);
    this.replicationQueue = replicationQueue;
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
    reloadIfNeeded();
    return currentConfig.getDestinations(filterType);
  }

  private void reloadIfNeeded() {
    if (isAutoReload()) {
      ReplicationQueue queue = replicationQueue.get();
      long lastModified = getLastModified(currentConfig);
      try {
        if (lastModified > currentConfigTs && lastModified > lastFailedConfigTs) {
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
  }
}
