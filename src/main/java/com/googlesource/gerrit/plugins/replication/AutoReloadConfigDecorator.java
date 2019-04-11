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
  private static final String EMPTY_ETAG = "";

  private ReplicationConfig currentConfig;
  private String currentConfigEtag;
  private String lastFailedConfigEtag;

  private final SitePaths site;
  private final Path pluginDataDir;
  private final Provider<ReplicationConfigListener> configListener;

  @Inject
  public AutoReloadConfigDecorator(
      SitePaths site,
      @PluginData Path pluginDataDir,
      Provider<ReplicationConfigListener> configListener)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.pluginDataDir = pluginDataDir;
    configListener.get().beforeLoad();
    this.currentConfig = loadConfig();
    configListener.get().afterLoad(this);
    this.currentConfigEtag = currentConfig.getETag();
    this.configListener = configListener;
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
    reloadIfNeeded();
    return currentConfig.getConfig();
  }

  private synchronized void reloadIfNeeded() {
    if (isAutoReload()) {
      String currentEtag = currentConfigEtag;
      ReplicationConfig lastConfig = currentConfig;
      try {
        if (!currentEtag.equals(currentConfigEtag) && !currentEtag.equals(lastFailedConfigEtag)) {
          configListener.get().beforeLoad();
          currentConfig = loadConfig();
          currentConfigEtag = currentEtag;
          lastFailedConfigEtag = EMPTY_ETAG;
          configListener.get().afterLoad(this);
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log(
            "Cannot reload replication configuration: keeping existing settings");
        currentConfig = lastConfig;
        lastFailedConfigEtag = currentEtag;
      }
    }
  }

  private ReplicationFileBasedConfig loadConfig() throws ConfigInvalidException, IOException {
    return new ReplicationFileBasedConfig(site, pluginDataDir);
  }

  private synchronized boolean isAutoReload() {
    return currentConfig.getConfig().getBoolean("gerrit", "autoReload", false);
  }

  @Override
  public String getETag() {
    return currentConfigEtag;
  }
}
