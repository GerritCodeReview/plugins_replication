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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.Destination.Factory;
import java.nio.file.Path;

public class AutoReloadRunnable implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Provider<ReplicationConfigListener> configListener;
  private ReplicationFileBasedConfig config;
  private String configVersion;
  private String lastFailedConfigVersion;
  private SitePaths site;
  private Factory destinationFactory;
  private Path pluginDataDir;

  @Inject
  public AutoReloadRunnable(
      Provider<ReplicationConfigListener> configListener,
      ReplicationFileBasedConfig config,
      SitePaths site,
      Destination.Factory destinationFactory,
      @PluginData Path pluginDataDir) {
    this.configListener = configListener;
    this.config = config;
    this.configVersion = config.getVersion();
    this.lastFailedConfigVersion = "";
    this.site = site;
    this.destinationFactory = destinationFactory;
    this.pluginDataDir = pluginDataDir;
  }

  @Override
  public synchronized void run() {
    String lastVersion = config.getVersion();
    try {
      if (!lastVersion.equals(configVersion) && !lastVersion.equals(lastFailedConfigVersion)) {
        configListener.get().beforeReload(config);
        ReplicationFileBasedConfig newConfig =
            new ReplicationFileBasedConfig(site, destinationFactory, pluginDataDir);
        configListener.get().afterReload(newConfig);
        config = newConfig;
        configVersion = newConfig.getVersion();
        lastFailedConfigVersion = "";
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      lastFailedConfigVersion = lastVersion;
    }
  }
}
