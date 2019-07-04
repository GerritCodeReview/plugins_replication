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

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Before;
import org.junit.Test;

public class AutoReloadConfigDecoratorTest extends AbstractConfigTest {
  ReplicationFileBasedConfig replicationFileBasedConfig;
  private DynamicSet<ReplicationConfigValidator> configValidators;

  public AutoReloadConfigDecoratorTest() throws IOException {
    super();
  }

  @Override
  @Before
  public void setup() {
    super.setup();

    configValidators = new DynamicSet<>();
    replicationFileBasedConfig = newReplicationFileBasedConfig();
  }

  @Test
  public void shouldLoadNotEmptyInitialReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.somewhere.com/${name}");
    replicationConfig.save();

    assertThat(newDestinationsCollections(replicationFileBasedConfig).getAll(FilterType.ALL))
        .isNotEmpty();
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", true);
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.foo.com/${name}");
    replicationConfig.save();

    newAutoReloadConfig().start();
    DestinationsCollection destinations = newDestinationsCollections(replicationFileBasedConfig);

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(2);
  }

  @Test
  public void shouldNotAutoReloadReplicationConfigIfDisabled() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", false);
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.foo.com/${name}");
    replicationConfig.save();

    DestinationsCollection destinations = newDestinationsCollections(replicationFileBasedConfig);
    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);
  }

  private AutoReloadConfigDecorator newAutoReloadConfig() {
    AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            configValidators,
            replicationFileBasedConfig,
            sitePaths,
            pluginDataPath,
            eventBus,
            Providers.of(replicationQueueMock));
    AutoReloadConfigDecorator configDecorator =
        new AutoReloadConfigDecorator(
            "replication", workQueueMock, replicationFileBasedConfig, autoReloadRunnable, eventBus);
    configDecorator.start();
    return configDecorator;
  }

  private ReplicationFileBasedConfig newReplicationFileBasedConfig() {
    return new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
  }
}
