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

import org.eclipse.jgit.lib.Config;

/**
 * Separates configuration resource from the configuration schema.
 *
 * <p>The resource here is {@link Config} object any requirements for provided options. Here we just
 * provide the raw data without any assumptions, the higher layer (namely {@link ReplicationConfig}
 * is responsible for extracting properties and providing the configuration for the plugin.
 */
public interface ConfigResource {

  /**
   * Configuration for the plugin and all replication end points.
   *
   * @return current configuration
   */
  Config getConfig();

  /**
   * Current logical version string of the current configuration loaded in memory, depending on the
   * actual implementation of the configuration on the persistent storage.
   *
   * @return current logical version number.
   */
  String getVersion();
}
