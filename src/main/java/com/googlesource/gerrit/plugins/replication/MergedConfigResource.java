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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.UsedAt.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.api.ConfigResource;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfigOverrides;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

public class MergedConfigResource {
  @VisibleForTesting
  @UsedAt(Project.PLUGIN_PULL_REPLICATION)
  public static MergedConfigResource withBaseOnly(ConfigResource base) {
    return new MergedConfigResource(Providers.of(base), null);
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Provider<ConfigResource> base;
  @Nullable private final DynamicItem<ReplicationConfigOverrides> overrides;

  @Inject
  MergedConfigResource(
      Provider<ConfigResource> base, @Nullable DynamicItem<ReplicationConfigOverrides> overrides) {
    this.base = base;
    this.overrides = overrides;
  }

  public Config getConfig() {
    Config config = base.get().getConfig();
    if (noOverrides()) {
      return config;
    }

    String overridesText = overrides.get().getConfig().toText();
    if (!overridesText.isEmpty()) {
      try {
        config.fromText(overridesText);
      } catch (ConfigInvalidException e) {
        logger.atWarning().withCause(e).log("Failed to merge replication config overrides");
      }
    }

    return config;
  }

  public String getVersion() {
    String baseVersion = base.get().getVersion();
    if (noOverrides()) {
      return baseVersion;
    }

    return baseVersion + overrides.get().getVersion();
  }

  private boolean noOverrides() {
    return overrides == null || overrides.get() == null;
  }
}
