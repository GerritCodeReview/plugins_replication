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
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;

public class AutoReloadRunnable implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private DynamicSet<ReplicationConfigListener> configListeners;
  private ReplicationFileBasedConfig loadedConfig;
  private String loadedConfigVersion;
  private Provider<EventBusConfigurationChange> eventBusConfigurationChangeProvider;
  private String lastFailedConfigVersion;
  private SitePaths site;
  private Path pluginDataDir;

  @Inject
  public AutoReloadRunnable(
      DynamicSet<ReplicationConfigListener> configListeners,
      ReplicationFileBasedConfig config,
      SitePaths site,
      Provider<EventBusConfigurationChange> eventBusConfigurationChangeProvider,
      @PluginData Path pluginDataDir) {
    this.configListeners = configListeners;
    this.loadedConfig = config;
    this.loadedConfigVersion = config.getVersion();
    this.eventBusConfigurationChangeProvider = eventBusConfigurationChangeProvider;
    this.lastFailedConfigVersion = "";
    this.site = site;
    this.pluginDataDir = pluginDataDir;
  }

  @Override
  public synchronized void run() {
    String pendingConfigVersion = loadedConfig.getVersion();
    try {
      if (!pendingConfigVersion.equals(loadedConfigVersion)
          && !pendingConfigVersion.equals(lastFailedConfigVersion)) {
        ReplicationFileBasedConfig newConfig = new ReplicationFileBasedConfig(site, pluginDataDir);
        final ConfigurationChangeEvent configurationChangeEvent =
            new ConfigurationChangeEvent(loadedConfig, newConfig);

        fireValidations(configurationChangeEvent);
        eventBusConfigurationChangeProvider.get().fireAndForget(configurationChangeEvent);

        loadedConfig = newConfig;
        loadedConfigVersion = newConfig.getVersion();
        lastFailedConfigVersion = "";
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      lastFailedConfigVersion = pendingConfigVersion;
    }
  }

  private void fireValidations(ConfigurationChangeEvent configurationChangeEvent)
      throws ConfigInvalidException {
    for (ReplicationConfigListener listener : configListeners) {
      listener.validateNewConfig(configurationChangeEvent);
    }
  }
}
