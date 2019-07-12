// Copyright (C) 2019 The Android Open Source Project
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
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.nio.file.Path;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class EventsDirectoryProvider implements Provider<Path> {
  private final FileBasedConfig config;
  private final SitePaths site;
  private final Path pluginDataDir;

  @Inject
  public EventsDirectoryProvider(SitePaths site, @PluginData Path pluginDataDir) {
    Path cfgPath = site.etc_dir.resolve("replication.config");
    this.site = site;
    this.pluginDataDir = pluginDataDir;
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
  }

  @Override
  public Path get() {
    String eventsDirectory = config.getString("replication", null, "eventsDirectory");
    if (!Strings.isNullOrEmpty(eventsDirectory)) {
      return site.resolve(eventsDirectory);
    }
    return pluginDataDir;
  }
}
