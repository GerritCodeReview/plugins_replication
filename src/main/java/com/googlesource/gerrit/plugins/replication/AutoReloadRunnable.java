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

import com.google.common.eventbus.EventBus;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.Destination.Factory;
import java.nio.file.Path;

public class AutoReloadRunnable implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ReplicationFileBasedConfig loadedConfig;
  private String loadedConfigVersion;
  private String lastFailedConfigVersion;
  private SitePaths site;
  private Factory destinationFactory;
  private Path pluginDataDir;
  private final EventBus eventBus;

  @Inject
  public AutoReloadRunnable(
      ReplicationFileBasedConfig config,
      SitePaths site,
      Destination.Factory destinationFactory,
      @PluginData Path pluginDataDir,
      EventBus eventBus) {
    this.loadedConfig = config;
    this.loadedConfigVersion = config.getVersion();
    this.lastFailedConfigVersion = "";
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.pluginDataDir = pluginDataDir;
    this.eventBus = eventBus;
  }

  @Override
  public synchronized void run() {
    String pendingConfigVersion = loadedConfig.getVersion();
    try {
      if (!pendingConfigVersion.equals(loadedConfigVersion)
          && !pendingConfigVersion.equals(lastFailedConfigVersion)) {
        ReplicationFileBasedConfig newConfig =
            new ReplicationFileBasedConfig(site, destinationFactory, pluginDataDir);
        loadedConfig = newConfig;
        loadedConfigVersion = newConfig.getVersion();
        lastFailedConfigVersion = "";
        eventBus.post(loadedConfig);
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      lastFailedConfigVersion = pendingConfigVersion;
    }
  }
}
