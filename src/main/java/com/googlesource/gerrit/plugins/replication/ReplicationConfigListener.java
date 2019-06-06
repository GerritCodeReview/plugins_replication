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

import org.eclipse.jgit.errors.ConfigInvalidException;

/** Listener of the configuration events. */
public interface ReplicationConfigListener {

  /**
   * Invoked just before replication.config is about to be reloaded.
   *
   * @param oldConfig old replication config
   */
  void beforeReload(ReplicationFileBasedConfig oldConfig);

  /**
   * Invoked just after replication.config has been reloaded.
   *
   * @param newConfig new replication config
   * @throws ConfigInvalidException if the loaded configuration is not valid
   */
  void afterReload(ReplicationFileBasedConfig newConfig) throws ConfigInvalidException;
}
