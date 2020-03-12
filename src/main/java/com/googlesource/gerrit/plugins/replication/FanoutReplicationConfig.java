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

import static com.google.common.io.Files.getFileExtension;
import static com.google.common.io.Files.getNameWithoutExtension;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class FanoutReplicationConfig implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ReplicationFileBasedConfig replicationConfig;
  private final Config config;
  private final Path remoteConfigsDirPath;
  private FileSnapshot remoteConfigsDirSnapshot;

  @Inject
  public FanoutReplicationConfig(SitePaths site, @PluginData Path pluginDataDir)
      throws IOException, ConfigInvalidException {

    remoteConfigsDirPath = site.etc_dir.resolve("replication");
    replicationConfig = new ReplicationFileBasedConfig(site, pluginDataDir);
    remoteConfigsDirSnapshot = FileSnapshot.save(remoteConfigsDirPath.toFile());

    config = replicationConfig.getConfig();
    removeRemotes(config);

    try (Stream<Path> files = Files.walk(remoteConfigsDirPath, 1)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> getFileExtension(path.toString()).equals("config"))
          .map(this::loadConfig)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .filter(this::isValid)
          .forEach(cfg -> addRemoteConfig(cfg, config));
    } catch (IllegalStateException e) {
      throw new ConfigInvalidException(e.getMessage());
    }
  }

  private void removeRemotes(Config config) {
    Set<String> remoteNames = config.getSubsections("remote");
    if (remoteNames.size() > 0) {
      logger.atSevere().log(
          "When replication directory is present replication.config file cannot contain remote configuration. Ignoring: %s",
          String.join(",", remoteNames));

      for (String name : remoteNames) {
        config.unsetSection("remote", name);
      }
    }
  }

  private void addRemoteConfig(FileBasedConfig source, Config destination) {
    String remoteName = getNameWithoutExtension(source.getFile().getName());
    for (String name : source.getNames("remote")) {
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)));
    }
  }

  private boolean isValid(Config cfg) {
    if (cfg.getSections().size() != 1 || !cfg.getSections().contains("remote")) {
      logger.atSevere().log(
          "Remote replication configuration file %s must contain only one remote section.", cfg);
      return false;
    }
    if (cfg.getSubsections("remote").size() > 0) {
      logger.atSevere().log(
          "Remote replication configuration file %s cannot contain remote subsections.", cfg);
      return false;
    }

    return true;
  }

  private Optional<FileBasedConfig> loadConfig(Path path) {
    FileBasedConfig cfg = new FileBasedConfig(path.toFile(), FS.DETECTED);
    try {
      cfg.load();
    } catch (IOException | ConfigInvalidException e) {
      logger.atSevere().withCause(e).log(
          "Cannot load remote replication configuration file %s.", path);
      return Optional.empty();
    }
    return Optional.of(cfg);
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
    if (remoteConfigsDirSnapshot.isModified(remoteConfigsDirPath.toFile())) {
      remoteConfigsDirSnapshot = FileSnapshot.save(remoteConfigsDirPath.toFile());
    }

    return remoteConfigsDirSnapshot.lastModifiedInstant().toEpochMilli();
  }
}
