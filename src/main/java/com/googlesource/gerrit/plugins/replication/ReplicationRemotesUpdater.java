// Copyright (C) 2024 The Android Open Source Project
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

import static com.google.gerrit.common.UsedAt.Project.PLUGIN_GITHUB;

import com.google.gerrit.common.UsedAt;
import com.google.gerrit.server.securestore.SecureStore;
import java.io.IOException;
import org.eclipse.jgit.lib.Config;

@UsedAt(PLUGIN_GITHUB)

/** Public API to update replication plugin remotes configurations programmatically. */
public interface ReplicationRemotesUpdater {

  /**
   * Adds or updates the remote configuration for the replication plugin.
   *
   * <p>Provided JGit {@link Config} object should contain at least one named <em>remote</em>
   * section. All other configurations will be ignored.
   *
   * <p>NOTE: The {@code remote.$name.password} will be stored using {@link SecureStore}.
   *
   * @param remoteConfig remotes to add or update
   * @throws IOException when persisting fails
   */
  void update(Config remoteConfig) throws IOException;
}
