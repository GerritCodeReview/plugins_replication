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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

@Singleton
public class DynamicReplicationFileBasedConfigs {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private List<FileBasedConfig> configs = Collections.emptyList();
  private Path replicationConfigDirPath;

  @Inject
  public DynamicReplicationFileBasedConfigs(SitePaths site) throws IOException {
    this.replicationConfigDirPath = site.etc_dir.resolve("replication.config.d");

    if (Files.exists(replicationConfigDirPath)) {
      try (Stream<Path> files = Files.walk(site.etc_dir.resolve("replication.config.d"))) {
        configs =
            files
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().endsWith(".config"))
                .map(path -> new FileBasedConfig(path.toFile(), FS.DETECTED))
                .collect(Collectors.toList());
      }
    }
  }

  public List<FileBasedConfig> getConfigs() {
    return configs;
  }

  public String getVersion() {
    try {
      if (!Files.exists(replicationConfigDirPath)) {
        return Long.toString(Long.MIN_VALUE);
      }
      return Long.toString(Files.getLastModifiedTime(replicationConfigDirPath).toMillis());

    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read configuration version for dynamic replication config files");
      return Long.toString(Long.MIN_VALUE);
    }
  }
}
