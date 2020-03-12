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
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class FanoutReplicationConfig implements ReplicationConfig {

  private ReplicationFileBasedConfig replicationConfig;
  private Config config;
  List<Config> replicationRemoteconfigs = Collections.emptyList();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Path remoteConfigsDirPath;

  public FanoutReplicationConfig(SitePaths site, @PluginData Path pluginDataDir)
      throws IOException, ConfigInvalidException {
    this.remoteConfigsDirPath = site.etc_dir.resolve("replication.remotes.d");
    this.replicationConfig = new ReplicationFileBasedConfig(site, pluginDataDir);

    try (Stream<Path> files = Files.walk(remoteConfigsDirPath)) {
      config = replicationConfig.getConfig();

      removeRemotesFromReplicationConfig(config);

      files
          .filter(Files::isRegularFile)
          .filter(
              path -> com.google.common.io.Files.getFileExtension(path.toString()).equals("config"))
          .map(
              path -> {
                FileBasedConfig cfg = new FileBasedConfig(path.toFile(), FS.DETECTED);
                try {
                  cfg.load();
                } catch (IOException | ConfigInvalidException e) {
                  throw new IllegalStateException(e);
                }
                return cfg;
              })
          .filter(this::validateRemoteConfig)
          .forEach(cfg -> addRemoteConfig(cfg, config));
    } catch (IllegalStateException e) {
      throw new ConfigInvalidException(e.getMessage());
    }
  }

  private void removeRemotesFromReplicationConfig(Config config) {
    Set<String> remoteNames = config.getSubsections("remote");
    if (remoteNames.size() > 0) {
      logger.atSevere().log(
          "When replication.remotes.d directory is present replication.config file cannot contain remote configuration. Ignoring: %s",
          String.join(",", remoteNames));

      for (String name : remoteNames) {
        config.unsetSection("remote", name);
      }
    }
  }

  private void addRemoteConfig(FileBasedConfig source, Config destination) {
    String remoteName =
        com.google.common.io.Files.getNameWithoutExtension(source.getFile().getName());
    for (String name : source.getNames("remote")) {
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)));
    }
  }

  private boolean validateRemoteConfig(Config cfg) {
    if (cfg.getSections().size() != 1 || !cfg.getSections().contains("remote")) {
      logger.atSevere().log(
          "Remote replication configuration file %s can contain only remote section. Ignoring configuration.",
          cfg);
      return false;
    }
    if (cfg.getSubsections("remote").size() > 0) {
      logger.atSevere().log(
          "Remote replication configuration file %s cannot contain remote subsections. Ignoring configuration.",
          cfg);
      return false;
    }

    return true;
  }

  @Override
  public boolean isReplicateAllOnPluginStart() {
    return replicationConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public boolean isDefaultForceUpdate() {
    return replicationConfig.isDefaultForceUpdate();
  }

  @Override
  public int getMaxRefsToLog() {
    return replicationConfig.getMaxRefsToLog();
  }

  @Override
  public Path getEventsDirectory() {
    return replicationConfig.getEventsDirectory();
  }

  @Override
  public int getSshConnectionTimeout() {
    return replicationConfig.getSshConnectionTimeout();
  }

  @Override
  public int getSshCommandTimeout() {
    return replicationConfig.getSshCommandTimeout();
  }

  @Override
  public String getVersion() {
    return Long.toString(
        Math.max(
            Long.valueOf(replicationConfig.getVersion()), getRemoteReplicationDirectoryVersion()));
  }

  @Override
  public Config getConfig() {
    return config;
  }

  private Long getRemoteReplicationDirectoryVersion() {
    try {
      if (!Files.exists(remoteConfigsDirPath)) {
        return Long.MIN_VALUE;
      }
      return Files.getLastModifiedTime(remoteConfigsDirPath).toMillis();

    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read configuration version for dynamic replication config files");
      return Long.MIN_VALUE;
    }
  }
}
