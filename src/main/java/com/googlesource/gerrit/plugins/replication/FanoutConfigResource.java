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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
  private final Config fanoutConfig;

  @Inject
  FanoutConfigResource(SitePaths site) throws IOException, ConfigInvalidException {
    super(site);
    this.remoteConfigsDirPath = site.etc_dir.resolve(CONFIG_DIR);
    removeRemotes(config);

    try (Stream<Path> files = Files.list(remoteConfigsDirPath)) {
      Stream<String> mergedConfigs =
          files
              .filter(Files::isRegularFile)
              .filter(FanoutConfigResource::isConfig)
              .flatMap(
                  path ->
                      getConfigLines(
                          path,
                          (line) -> {
                            if (line.contains("[remote]")) {
                              return line.replace(
                                  "[remote]",
                                  "[remote \""
                                      + getNameWithoutExtension(path.toFile().getName())
                                      + "\"]");
                            } else {
                              return line;
                            }
                          }));

      fanoutConfig = new Config(config);
      fanoutConfig.fromText(mergedConfigs.collect(Collectors.joining("\n")));
    }
  }

  private static Stream<String> getConfigLines(
      Path path, Function<String, String> configLineMapping) {
    try {
      List<String> configLines = Files.readAllLines(path);
      if (!isValid(path, configLines)) {
        return Stream.empty();
      }
      return configLines.stream().map(configLineMapping);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Unable to access replication config %s", path);
      return Stream.empty();
    }
  }

  @Override
  public void update(Config updates) throws IOException {
    super.update(filterOutRemotes(updates));

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
        config.setStringList("remote", remote, option, values);
      }
      remoteConfig.save();
    }
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

  private static Config filterOutRemotes(Config config) {
    Config filteredConfig = new Config();
    Set<String> sections = config.getSections();
    sections.stream()
        .filter(Predicate.not("remote"::equalsIgnoreCase))
        .forEach(
            section -> {
              config
                  .getNames(section)
                  .forEach(
                      sectionName ->
                          filteredConfig.setStringList(
                              section,
                              null,
                              sectionName,
                              List.of(config.getStringList(section, null, sectionName))));
              config
                  .getSubsections(section)
                  .forEach(
                      subsection ->
                          config
                              .getNames(section, subsection)
                              .forEach(
                                  name ->
                                      filteredConfig.setStringList(
                                          section,
                                          subsection,
                                          name,
                                          List.of(
                                              config.getStringList(section, subsection, name)))));
            });
    return filteredConfig;
  }

  private static boolean isValid(Path path, List<String> remoteConfigLines) {
    int numRemoteSections = 0;
    int numRemoteSectionsWithName = 0;
    for (String configLine : remoteConfigLines) {
      if (configLine.contains("[remote]")) {
        numRemoteSections++;
      }

      if (configLine.contains("[remote \"")) {
        numRemoteSectionsWithName++;
      }
    }

    if (numRemoteSectionsWithName > 0) {
      logger.atSevere().log(
          "Remote replication configuration file %s cannot contain remote subsections.", path);
      return false;
    }

    if (numRemoteSections != 1) {
      logger.atSevere().log(
          "Remote replication configuration file %s must contain only one remote section.", path);
      return false;
    }

    return true;
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

  @Override
  public Config getConfig() {
    return fanoutConfig;
  }
}
