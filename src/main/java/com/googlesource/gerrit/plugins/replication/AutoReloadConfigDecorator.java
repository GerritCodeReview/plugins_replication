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

import com.google.gerrit.common.FileUtil;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final Logger log = LoggerFactory.getLogger(AutoReloadConfigDecorator.class);
  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;

  private final SitePaths site;
  private final WorkQueue workQueue;
  private final DestinationFactory destinationFactory;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site, WorkQueue workQueue, DestinationFactory destinationFactory)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.currentConfig = loadConfig();
    this.currentConfigTs = getLastModified(currentConfig);
    this.workQueue = workQueue;
  }

  private static long getLastModified(ReplicationFileBasedConfig cfg) {
    return FileUtil.lastModified(cfg.getCfgPath());
  }

  private ReplicationFileBasedConfig loadConfig() throws ConfigInvalidException, IOException {
    return new ReplicationFileBasedConfig(site, destinationFactory);
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
    try {
      if (isAutoReload()) {
        long lastModified = getLastModified(currentConfig);
        if (lastModified > currentConfigTs) {
          ReplicationFileBasedConfig newConfig = loadConfig();
          newConfig.startup(workQueue);
          int discarded = currentConfig.shutdown();

          this.currentConfig = newConfig;
          this.currentConfigTs = lastModified;
          log.info(
              "Configuration reloaded: {} destinations, {} replication events discarded",
              currentConfig.getDestinations(FilterType.ALL).size(),
              discarded);
        }
      }
    } catch (Exception e) {
      log.error("Cannot reload replication configuration: keeping existing settings", e);
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
  public synchronized int shutdown() {
    return currentConfig.shutdown();
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    currentConfig.startup(workQueue);
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
