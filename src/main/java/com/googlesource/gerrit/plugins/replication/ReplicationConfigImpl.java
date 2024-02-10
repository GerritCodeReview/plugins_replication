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
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Config;

public class ReplicationConfigImpl implements ReplicationConfig {
  private static final int DEFAULT_SSH_CONNECTION_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes

  private final SitePaths site;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private int maxRefsToLog;
  private final int maxRefsToShow;
  private int sshCommandTimeout;
  private int sshConnectionTimeout = DEFAULT_SSH_CONNECTION_TIMEOUT_MS;
  private final MergedConfigResource configResource;
  private final Path pluginDataDir;

  @Inject
  public ReplicationConfigImpl(
      MergedConfigResource configResource, SitePaths site, @PluginData Path pluginDataDir) {
    this.site = site;
    this.configResource = configResource;
    Config config = configResource.getConfig();
    this.replicateAllOnPluginStart = config.getBoolean("gerrit", "replicateOnStartup", false);
    this.defaultForceUpdate = config.getBoolean("gerrit", "defaultForceUpdate", false);
    this.maxRefsToLog = config.getInt("gerrit", "maxRefsToLog", 0);
    this.maxRefsToShow = config.getInt("gerrit", "maxRefsToShow", 2);
    this.pluginDataDir = pluginDataDir;
  }

  @Nullable
  public static String replaceName(String in, String name, boolean keyIsOptional) {
    String key = "${name}";
    int n = in.indexOf(key);
    if (0 <= n) {
      return in.substring(0, n) + name + in.substring(n + key.length());
    }
    if (keyIsOptional) {
      return in;
    }
    return null;
  }

  /**
   * See {@link
   * com.googlesource.gerrit.plugins.replication.api.ReplicationConfig#isReplicateAllOnPluginStart()}
   */
  @Override
  public boolean isReplicateAllOnPluginStart() {
    return replicateAllOnPluginStart;
  }

  /**
   * See {@link
   * com.googlesource.gerrit.plugins.replication.api.ReplicationConfig#isDefaultForceUpdate()}
   */
  @Override
  public boolean isDefaultForceUpdate() {
    return defaultForceUpdate;
  }

  @Override
  public int getDistributionInterval() {
    return getConfig().getInt("replication", "distributionInterval", 0);
  }

  @Override
  public int getMaxRefsToLog() {
    return maxRefsToLog;
  }

  @Override
  public int getMaxRefsToShow() {
    return maxRefsToShow;
  }

  @Override
  public Path getEventsDirectory() {
    String eventsDirectory = getConfig().getString("replication", null, "eventsDirectory");
    if (!Strings.isNullOrEmpty(eventsDirectory)) {
      return site.resolve(eventsDirectory);
    }
    return pluginDataDir;
  }

  @Override
  public Config getConfig() {
    return configResource.getConfig();
  }

  @Override
  public String getVersion() {
    return configResource.getVersion();
  }

  @Override
  public int getSshConnectionTimeout() {
    return sshConnectionTimeout;
  }

  @Override
  public int getSshCommandTimeout() {
    return sshCommandTimeout;
  }
}
