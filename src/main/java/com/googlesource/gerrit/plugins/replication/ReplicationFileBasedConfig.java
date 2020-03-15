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
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import java.nio.file.Path;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class ReplicationFileBasedConfig implements ReplicationConfig {
  private static final int DEFAULT_SSH_CONNECTION_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes

  private final SitePaths site;
  private Path cfgPath;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private int maxRefsToLog;
  private int sshCommandTimeout;
  private int sshConnectionTimeout = DEFAULT_SSH_CONNECTION_TIMEOUT_MS;
  private final FileBasedConfig config;
  private final Path pluginDataDir;

  public ReplicationFileBasedConfig(SitePaths site, @PluginData Path pluginDataDir) {
    this.site = site;
    this.cfgPath = site.etc_dir.resolve("replication.config");
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    this.replicateAllOnPluginStart = config.getBoolean("gerrit", "replicateOnStartup", false);
    this.defaultForceUpdate = config.getBoolean("gerrit", "defaultForceUpdate", false);
    this.maxRefsToLog = config.getInt("gerrit", "maxRefsToLog", 0);
    this.pluginDataDir = pluginDataDir;
  }

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
  public int getMaxRefsToLog() {
    return maxRefsToLog;
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
  public FileBasedConfig getConfig() {
    return config;
  }

  @Override
  public String getVersion() {
    return Long.toString(config.getFile().lastModified());
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
