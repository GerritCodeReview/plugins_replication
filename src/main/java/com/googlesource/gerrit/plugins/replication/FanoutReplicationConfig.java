// Copyright (C) 2020 The Android Open Source Project
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

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class FanoutReplicationConfig implements ReplicationConfig {

  private ReplicationFileBasedConfig mainReplicationConfig;
  private Config config;
  List<Config> replicationRemoteconfigs = Collections.emptyList();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Path replicationConfigDirPath;

  public FanoutReplicationConfig(SitePaths site, @PluginData Path pluginDataDir)
      throws IOException {
    this.replicationConfigDirPath = site.etc_dir.resolve("replication.remotes.d");
    this.mainReplicationConfig = new ReplicationFileBasedConfig(site, pluginDataDir);

    try {
      mainReplicationConfig.getConfig().load();
      config = mainReplicationConfig.getConfig();
    } catch (ConfigInvalidException e) {
      throw new IllegalStateException(e);
    }

    try (Stream<Path> files = Files.walk(replicationConfigDirPath)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().toLowerCase().endsWith(".config"))
          .map(path -> new FileBasedConfig(path.toFile(), FS.DETECTED))
          .map(
              cfg -> {
                try {
                  cfg.load();
                  if (cfg.getSections().size() != 1 || !cfg.getSections().contains("remote")) {
                    throw new IllegalStateException(
                        String.format(
                            "Additional replication configuration file %s can contain only remote section",
                            cfg));
                  }
                } catch (IOException | ConfigInvalidException e) {
                  throw new IllegalStateException(e);
                }
                return cfg;
              })
          .forEach(
              cfg -> {
                mergeAdditionalConfig(
                    cfg,
                    config,
                    com.google.common.io.Files.getNameWithoutExtension(cfg.getFile().getName()));
              });
    }
  }

  private void mergeAdditionalConfig(Config source, Config destination, String remoteName) {
    for (String name : source.getNames("remote", true)) {
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)));
    }
  }

  @Override
  public boolean isReplicateAllOnPluginStart() {
    return mainReplicationConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public boolean isDefaultForceUpdate() {
    return mainReplicationConfig.isDefaultForceUpdate();
  }

  @Override
  public int getMaxRefsToLog() {
    return mainReplicationConfig.getMaxRefsToLog();
  }

  @Override
  public Path getEventsDirectory() {
    return mainReplicationConfig.getEventsDirectory();
  }

  @Override
  public int getSshConnectionTimeout() {
    return mainReplicationConfig.getSshConnectionTimeout();
  }

  @Override
  public int getSshCommandTimeout() {
    return mainReplicationConfig.getSshCommandTimeout();
  }

  @Override
  public String getVersion() {
    return Long.toString(
        Math.max(
            Long.valueOf(mainReplicationConfig.getVersion()),
            getRemoteReplicationDirectoryVersion()));
  }

  @Override
  public Config getConfig() {
    return config;
  }

  private Long getRemoteReplicationDirectoryVersion() {
    try {
      if (!Files.exists(replicationConfigDirPath)) {
        return Long.MIN_VALUE;
      }
      return Files.getLastModifiedTime(replicationConfigDirPath).toMillis();

    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read configuration version for dynamic replication config files");
      return Long.MIN_VALUE;
    }
  }
}
