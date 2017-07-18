// Copyright (C) 2017 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.DestinationConfiguration.DEFAULT_REPLICATION_DELAY;
import static com.googlesource.gerrit.plugins.replication.DestinationConfiguration.DEFAULT_RESCHEDULE_DELAY;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.File;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class Init implements InitStep {
  private final String pluginName;
  private final SitePaths site;
  private final ConsoleUI ui;

  @Inject
  Init(@PluginName String pluginName, SitePaths site, ConsoleUI ui) {
    this.pluginName = pluginName;
    this.site = site;
    this.ui = ui;
  }

  @Override
  public void run() throws Exception {
    File configFile = site.etc_dir.resolve(pluginName + ".config").toFile();
    if (!configFile.exists()) {
      return;
    }

    FileBasedConfig config = new FileBasedConfig(configFile, FS.DETECTED);
    config.load();
    for (String name : config.getSubsections("remote")) {
      if (!Strings.isNullOrEmpty(config.getString("remote", name, "rescheduleDelay"))) {
        continue;
      }

      int replicationDelay =
          config.getInt("remote", name, "replicationDelay", DEFAULT_REPLICATION_DELAY);
      if (replicationDelay > 0) {
        int delay = Math.max(replicationDelay, DEFAULT_RESCHEDULE_DELAY);
        ui.message("Setting remote.%s.rescheduleDelay = %d\n", name, delay);
        config.setInt("remote", name, "rescheduleDelay", delay);
      } else {
        ui.message(
            "INFO: Assuming default (%d s) for remote.%s.rescheduleDelay\n",
            DEFAULT_RESCHEDULE_DELAY, name);
      }
    }
    config.save();
  }
}
