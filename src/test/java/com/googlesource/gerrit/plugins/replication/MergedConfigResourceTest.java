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

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.googlesource.gerrit.plugins.replication.api.ApiModule;
import com.googlesource.gerrit.plugins.replication.api.ConfigResource;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfigOverrides;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class MergedConfigResourceTest {
  private static final int BASE_CONFIG_MAX_RETIRES = 10;
  private static final int OVERRIDDEN_CONFIG_MAX_RETIRES = 5;

  @Test
  public void onlyUseBaseConfig() {
    final MergedConfigResource configResource = newMergedConfigResource();

    assertThat(configResource.getVersion()).isEqualTo("base");
    assertThat(getMaxRetires(configResource)).isEqualTo(BASE_CONFIG_MAX_RETIRES);
  }

  @Test
  public void overrideBaseConfig() {
    final MergedConfigResource configResource =
        newMergedConfigResource(TestReplicationConfigOverrides.class);

    assertThat(configResource.getVersion()).isEqualTo("baseoverride");
    assertThat(getMaxRetires(configResource)).isEqualTo(OVERRIDDEN_CONFIG_MAX_RETIRES);
    assertThat(getUseGcClient(configResource)).isTrue();
  }

  private MergedConfigResource newMergedConfigResource() {
    return newMergedConfigResource(null);
  }

  private MergedConfigResource newMergedConfigResource(
      Class<? extends ReplicationConfigOverrides> overrides) {
    return Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                install(new ApiModule());

                bind(com.googlesource.gerrit.plugins.replication.api.ConfigResource.class)
                    .to(TestBaseConfigResource.class);

                if (overrides != null) {
                  DynamicItem.bind(
                          binder(),
                          com.googlesource.gerrit.plugins.replication.api.ReplicationConfigOverrides
                              .class)
                      .to(overrides);
                }
              }
            })
        .getInstance(MergedConfigResource.class);
  }

  private static class TestBaseConfigResource implements ConfigResource {
    @Override
    public Config getConfig() {
      Config config = new Config();
      setMaxRetires(config, BASE_CONFIG_MAX_RETIRES);
      return config;
    }

    @Override
    public String getVersion() {
      return "base";
    }
  }

  private static class TestReplicationConfigOverrides
      implements com.googlesource.gerrit.plugins.replication.api.ReplicationConfigOverrides {
    @Override
    public Config getConfig() {
      Config config = new Config();
      setMaxRetires(config, OVERRIDDEN_CONFIG_MAX_RETIRES);
      setUseGcClient(config, true);
      return config;
    }

    @Override
    public String getVersion() {
      return "override";
    }
  }

  private static void setMaxRetires(Config config, int value) {
    config.setInt("replication", null, "maxRetries", value);
  }

  private static void setUseGcClient(Config config, boolean value) {
    config.setBoolean("replication", null, "useGcClient", value);
  }

  private static int getMaxRetires(MergedConfigResource resource) {
    return resource.getConfig().getInt("replication", null, "maxRetries", -1);
  }

  private static boolean getUseGcClient(MergedConfigResource resource) {
    return resource.getConfig().getBoolean("replication", null, "useGcClient", false);
  }
}
