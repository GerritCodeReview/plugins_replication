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

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

@Singleton
public class ReplicationFileBasedConfig implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SitePaths site;
  private Path cfgPath;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private final FileBasedConfig config;
  private final Path pluginDataDir;
  private final String configETag;

  @Inject
  public ReplicationFileBasedConfig(SitePaths site, @PluginData Path pluginDataDir)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.cfgPath = site.etc_dir.resolve("replication.config");
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    this.configETag = String.format("T%d" + config.getFile().lastModified());
    this.pluginDataDir = pluginDataDir;
    load();
  }

  private void load() throws ConfigInvalidException, IOException {
    if (!config.getFile().exists()) {
      logger.atWarning().log("Config file %s does not exist; not replicating", config.getFile());
    }
    if (config.getFile().length() == 0) {
      logger.atInfo().log("Config file %s is empty; not replicating", config.getFile());
    }

    try {
      config.load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(
          String.format("Config file %s is invalid: %s", config.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      throw new IOException(
          String.format("Cannot read %s: %s", config.getFile(), e.getMessage()), e);
    }

    replicateAllOnPluginStart = config.getBoolean("gerrit", "replicateOnStartup", false);

    defaultForceUpdate = config.getBoolean("gerrit", "defaultForceUpdate", false);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isReplicateAllOnPluginStart()
   */
  @Override
  public boolean isReplicateAllOnPluginStart() {
    return replicateAllOnPluginStart;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isDefaultForceUpdate()
   */
  @Override
  public boolean isDefaultForceUpdate() {
    return defaultForceUpdate;
  }

  @Override
  public Path getEventsDirectory() {
    String eventsDirectory = config.getString("replication", null, "eventsDirectory");
    if (!Strings.isNullOrEmpty(eventsDirectory)) {
      return site.resolve(eventsDirectory);
    }
    return pluginDataDir;
  }

  Path getCfgPath() {
    return cfgPath;
  }

  @Override
  public Config getConfig() {
    return config;
  }

  @Override
  public String getETag() {
    return configETag;
  }
}
