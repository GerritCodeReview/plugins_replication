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

import static com.google.common.io.Files.getNameWithoutExtension;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.file.FileSnapshot;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class FanoutConfigResource extends FileConfigResource {
  public static String CONFIG_DIR = "replication";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Path remoteConfigsDirPath;

  @Inject
  FanoutConfigResource(SitePaths site) throws IOException, ConfigInvalidException {
    super(site);
    this.remoteConfigsDirPath = site.etc_dir.resolve(CONFIG_DIR);
    removeRemotes(config);

    try (Stream<Path> files = Files.list(remoteConfigsDirPath)) {
      files
          .filter(Files::isRegularFile)
          .filter(FanoutConfigResource::isConfig)
          .map(FanoutConfigResource::loadConfig)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .filter(FanoutConfigResource::isValid)
          .forEach(cfg -> addRemoteConfig(cfg, config));
    } catch (IllegalStateException e) {
      throw new ConfigInvalidException(e.getMessage());
    }
  }

  @Override
  public void update(Config updates) throws IOException {
    Set<String> remotes = updates.getSubsections("remote");
    for (String remote : remotes) {
      File remoteFile = remoteConfigsDirPath.resolve(remote + ".config").toFile();
      FileBasedConfig remoteConfig = new FileBasedConfig(remoteFile, FS.DETECTED);
      try {
        remoteConfig.load();
      } catch (ConfigInvalidException e) {
        throw new IOException(
            String.format("Cannot parse configuration file: %s", remoteFile.getAbsoluteFile()), e);
      }
      Set<String> options = updates.getNames("remote", remote);
      for (String option : options) {
        List<String> values = List.of(updates.getStringList("remote", remote, option));
        remoteConfig.setStringList("remote", remote, option, values);
      }
      remoteConfig.save();
    }

    removeRemotes(updates);
    super.update(updates);
  }

  private static void removeRemotes(Config config) {
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

  private static void addRemoteConfig(FileBasedConfig source, Config destination) {
    String remoteName = getNameWithoutExtension(source.getFile().getName());
    for (String name : source.getNames("remote")) {
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)));
    }
  }

  private static boolean isValid(Config cfg) {
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

  private static Optional<FileBasedConfig> loadConfig(Path path) {
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

  private static boolean isConfig(Path p) {
    return p.toString().endsWith(".config");
  }

  @Override
  public String getVersion() {
    String parentVersion = super.getVersion();
    Hasher hasher = Hashing.murmur3_128().newHasher();
    hasher.putString(parentVersion, UTF_8);
    try (Stream<Path> files = Files.list(remoteConfigsDirPath)) {
      files
          .filter(Files::isRegularFile)
          .filter(FanoutConfigResource::isConfig)
          .sorted()
          .map(Path::toFile)
          .map(FileSnapshot::save)
          .forEach(
              fileSnapshot ->
                  // hashCode is based on file size, file key and last modified time
                  hasher.putInt(fileSnapshot.hashCode()));
      return hasher.hash().toString();
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot list remote configuration files from %s. Returning replication.config file version",
          remoteConfigsDirPath);
      return parentVersion;
    }
  }
}
