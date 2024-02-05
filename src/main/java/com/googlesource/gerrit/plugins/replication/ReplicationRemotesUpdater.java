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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Config;

/** Public API to update replication plugin remotes configurations programmatically. */
@Singleton
@UsedAt(UsedAt.Project.PLUGIN_GITHUB)
public class ReplicationRemotesUpdater {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SecureStore secureStore;
  private final Provider<ConfigResource> baseConfigProvider;
  private final DynamicItem<ReplicationConfigOverrides> configOverridesItem;

  @Inject
  ReplicationRemotesUpdater(
      SecureStore secureStore,
      Provider<ConfigResource> baseConfigProvider,
      @Nullable DynamicItem<ReplicationConfigOverrides> configOverridesItem) {
    this.secureStore = secureStore;
    this.baseConfigProvider = baseConfigProvider;
    this.configOverridesItem = configOverridesItem;
  }

  /**
   * Adds or update the remote configuration for the replication plugin.
   *
   * <p>Provided JGit {@link Config} object should contain at least one named <em>remote</em>
   * section. All other configurations will be ignored.
   *
   * <p>NOTE: The {@code remote.$name.password} will be stored using {@link SecureStore}.
   *
   * @param remoteConfig remotes to add or update
   * @throws IOException when persisting fails
   */
  public void update(Config remoteConfig) throws IOException {
    if (remoteConfig.getSubsections("remote").isEmpty()) {
      throw new IllegalArgumentException(
          "configuration update must have at least one 'remote' section");
    }
    Config remoteSectionsOnly = onlyRemoteSections(remoteConfig);
    if (hasConfigOverrides()) {
      remoteSectionsOnly = configOverridesItem.get().update(remoteSectionsOnly);
    }
    Config leftovers = baseConfigProvider.get().update(remoteSectionsOnly);
    if (!leftovers.getSections().isEmpty()) {
      logger.atSevere().log("not all configuration updates were persisted");
    }
  }

  private Config onlyRemoteSections(Config configUpdates) {
    Config onlyRemotes = new Config();
    for (String subSection : configUpdates.getSubsections("remote")) {
      for (String name : configUpdates.getNames("remote", subSection)) {
        List<String> values = List.of(configUpdates.getStringList("remote", subSection, name));
        if ("password".equals(name)) {
          secureStore.setList("remote", subSection, name, values);
        } else {
          onlyRemotes.setStringList("remote", subSection, name, values);
        }
      }
    }

    return onlyRemotes;
  }

  private boolean hasConfigOverrides() {
    return configOverridesItem != null && configOverridesItem.get() != null;
  }
}
