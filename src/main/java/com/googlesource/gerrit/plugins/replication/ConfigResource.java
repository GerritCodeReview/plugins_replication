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

import java.io.IOException;
import org.eclipse.jgit.lib.Config;

/**
 * Separates configuration resource from the configuration schema.
 *
 * <p>The {@code ConfigResource} splits configuration source from configuration options. The
 * resource is just an {@code Config} object with its version. The configuration options can be
 * loaded from different sources eg. a single file, multiple files or ZooKeeper.
 *
 * <p>Here we don't assume any configuration options being present in the {@link Config} object,
 * this is the responsibility of {@link ReplicationConfig} interface. Think about {@code
 * ConfigResource} as a "plain text file" and {@code ReplicationConfig} as a XML file.
 */
public interface ConfigResource {

  /**
   * Configuration for the plugin and all replication end points.
   *
   * @return current configuration
   */
  Config getConfig();

  /**
   * Update the configuration resource.
   *
   * <p>Allows to persist changes to the configuration resource.
   *
   * @param config updated configuration
   */
  void update(Config config) throws IOException;

  /**
   * Current logical version string of the current configuration loaded in memory, depending on the
   * actual implementation of the configuration on the persistent storage.
   *
   * @return current logical version number.
   */
  String getVersion();
}
