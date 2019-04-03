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
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;
  private long lastFailedConfigTs;

  private final SitePaths site;
  private final Path pluginDataDir;
  // Use Provider<> instead of injecting the ReplicationQueue because of circular dependency with
  // ReplicationConfig
  private final Provider<ReplicationQueue> replicationQueue;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site, Provider<ReplicationQueue> replicationQueue, @PluginData Path pluginDataDir)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.pluginDataDir = pluginDataDir;
    this.currentConfig = loadConfig();
    this.currentConfigTs = getLastModified(currentConfig);
    this.replicationQueue = replicationQueue;
  }

  private static long getLastModified(ReplicationFileBasedConfig cfg) {
    return FileUtil.lastModified(cfg.getCfgPath());
  }

  private ReplicationFileBasedConfig loadConfig() throws ConfigInvalidException, IOException {
    return new ReplicationFileBasedConfig(site, pluginDataDir);
  }

  private synchronized boolean isAutoReload() {
    return currentConfig.getConfig().getBoolean("gerrit", "autoReload", false);
  }

  @Override
  public synchronized boolean reloadIfNeeded() {
    if (isAutoReload()) {
      ReplicationQueue queue = replicationQueue.get();
      long lastModified = getLastModified(currentConfig);
      try {
        if (lastModified > currentConfigTs && lastModified > lastFailedConfigTs) {
          queue.stop();
          currentConfig = loadConfig();
          currentConfigTs = lastModified;
          lastFailedConfigTs = 0;

          return true;
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log(
            "Cannot reload replication configuration: keeping existing settings");
        lastFailedConfigTs = lastModified;
      } finally {
        queue.start();
      }
    }
    return false;
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
  public Path getEventsDirectory() {
    return currentConfig.getEventsDirectory();
  }

  @Override
  public Config getConfig() {
    return currentConfig.getConfig();
  }
}
