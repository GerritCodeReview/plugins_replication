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
import com.google.inject.Provider;
import java.nio.file.Path;
import java.util.List;

public class AutoReloadRunnable implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SitePaths site;
  private final Path pluginDataDir;
  private final EventBus eventBus;
  private final Provider<ObservableQueue> queueObserverProvider;
  private final ReplicationConfigParser configValidator;

  private ReplicationFileBasedConfig loadedConfig;
  private String loadedConfigVersion;
  private String lastFailedConfigVersion;

  @Inject
  public AutoReloadRunnable(
      ReplicationConfigParser configParser,
      ReplicationFileBasedConfig config,
      SitePaths site,
      @PluginData Path pluginDataDir,
      EventBus eventBus,
      Provider<ObservableQueue> queueObserverProvider) {
    this.loadedConfig = config;
    this.loadedConfigVersion = config.getVersion();
    this.lastFailedConfigVersion = "";
    this.site = site;
    this.pluginDataDir = pluginDataDir;
    this.eventBus = eventBus;
    this.queueObserverProvider = queueObserverProvider;
    this.configValidator = configParser;
  }

  @Override
  public synchronized void run() {
    String pendingConfigVersion = loadedConfig.getVersion();
    ObservableQueue queue = queueObserverProvider.get();
    if (pendingConfigVersion.equals(loadedConfigVersion)
        || pendingConfigVersion.equals(lastFailedConfigVersion)
        || !queue.isRunning()
        || queue.isReplaying()) {
      return;
    }

    reload();
  }

  synchronized void reload() {
    String pendingConfigVersion = loadedConfig.getVersion();
    try {
      ReplicationFileBasedConfig newConfig = new ReplicationFileBasedConfig(site, pluginDataDir);
      final List<RemoteConfiguration> newValidDestinations =
          configValidator.parse(newConfig);
      loadedConfig = newConfig;
      loadedConfigVersion = newConfig.getVersion();
      lastFailedConfigVersion = "";
      eventBus.post(newValidDestinations);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      lastFailedConfigVersion = pendingConfigVersion;
    }
  }
}
