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
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;

  private final SitePaths site;
  private final WorkQueue workQueue;
  private final DestinationFactory destinationFactory;
  private final Path pluginDataDir;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site,
      WorkQueue workQueue,
      DestinationFactory destinationFactory,
      @PluginData Path pluginDataDir)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.pluginDataDir = pluginDataDir;
    this.currentConfig = loadConfig();
    this.currentConfigTs = getLastModified(currentConfig);
    this.workQueue = workQueue;
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

  @Override
  public synchronized Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    reloadIfNeeded();
    return currentConfig.getURIs(remoteName, projectName, filterType);
  }

  private void reloadIfNeeded() {
    try {
      if (isAutoReload()) {
        long lastModified = getLastModified(currentConfig);
        if (lastModified > currentConfigTs) {
          ReplicationFileBasedConfig newConfig = loadConfig();
          newConfig.startup(workQueue);
          int discarded = currentConfig.shutdown();

          this.currentConfig = newConfig;
          this.currentConfigTs = lastModified;
          logger.atInfo().log(
              "Configuration reloaded: %d destinations, %d replication events discarded",
              currentConfig.getDestinations(FilterType.ALL).size(), discarded);
        }
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      return;
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
