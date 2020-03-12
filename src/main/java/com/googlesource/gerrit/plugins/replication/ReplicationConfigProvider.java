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

import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;

public class ReplicationConfigProvider implements Provider<ReplicationConfig> {
  SitePaths site;
  Path pluginDataDir;

  @Inject
  public ReplicationConfigProvider(SitePaths site, @PluginData Path pluginDataDir) {
    this.site = site;
    this.pluginDataDir = pluginDataDir;
  }

  @Override
  public ReplicationConfig get() {
    if (Files.exists(site.etc_dir.resolve("replication.remotes.d"))) {
      try {
        return new FanoutReplicationConfig(site, pluginDataDir);
      } catch (IOException | ConfigInvalidException e) {
        throw new IllegalStateException(
            String.format("Cannot read fanout config: %s", e.getMessage()), e);
      }
    }

    return new ReplicationFileBasedConfig(site, pluginDataDir);
  }
}
