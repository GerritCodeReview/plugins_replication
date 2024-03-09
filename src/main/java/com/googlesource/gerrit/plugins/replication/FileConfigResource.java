// Copyright (C) 2023 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.UsedAt.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class FileConfigResource implements ConfigResource {
  public static final String CONFIG_NAME = "replication.config";
  protected final FileBasedConfig config;

  @Inject
  @VisibleForTesting
  @UsedAt(Project.PLUGIN_PULL_REPLICATION)
  public FileConfigResource(SitePaths site) {
    Path cfgPath = site.etc_dir.resolve(CONFIG_NAME);
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    try {
      config.load();
    } catch (ConfigInvalidException e) {
      repLog.atSevere().withCause(e).log("Config file %s is invalid: %s", cfgPath, e.getMessage());
    } catch (IOException e) {
      repLog.atSevere().withCause(e).log("Cannot read %s: %s", cfgPath, e.getMessage());
    }
  }

  @Override
  public Config getConfig() {
    return config;
  }

  @Override
  public void update(Config updates) throws IOException {
    for (String section : updates.getSections()) {
      for (String subsection : updates.getSubsections(section)) {
        for (String name : updates.getNames(section, subsection, true)) {
          List<String> values =
              Lists.newArrayList(updates.getStringList(section, subsection, name));
          config.setStringList(section, subsection, name, values);
        }
      }

      for (String name : updates.getNames(section, true)) {
        List<String> values = Lists.newArrayList(updates.getStringList(section, null, name));
        config.setStringList(section, null, name, values);
      }
    }
    config.save();
  }

  @Override
  public String getVersion() {
    return Long.toString(config.getFile().lastModified());
  }
}
