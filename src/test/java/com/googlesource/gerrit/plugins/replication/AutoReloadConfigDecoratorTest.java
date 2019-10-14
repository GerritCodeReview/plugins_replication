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

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Before;
import org.junit.Test;

public class AutoReloadConfigDecoratorTest extends AbstractConfigTest {
  ReplicationFileBasedConfig replicationFileBasedConfig;

  public AutoReloadConfigDecoratorTest() throws IOException {
    super();
  }

  @Override
  @Before
  public void setup() {
    super.setup();

    replicationFileBasedConfig = newReplicationFileBasedConfig();
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", true);
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    replicationConfig.setString("remote", remoteName1, "url", remoteUrl1);
    replicationConfig.save();

    newAutoReloadConfig().start();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(replicationFileBasedConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteName2 = "bar";
    String remoteUrl2 = "ssh://git@git.bar.com/${name}";
    replicationConfig.setString("remote", remoteName2, "url", remoteUrl2);
    replicationConfig.save();
    executorService.refreshCommand.run();

    destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);
  }

  @Test
  public void shouldNotAutoReloadReplicationConfigIfDisabled() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", false);
    replicationConfig.setString("remote", remoteName1, "url", remoteUrl1);
    replicationConfig.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(replicationFileBasedConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinationsCollections.getAll(FilterType.ALL)).isEqualTo(destinations);
  }

  private AutoReloadConfigDecorator<DestinationConfiguration, ReplicationQueue> newAutoReloadConfig() throws ConfigInvalidException {
    AutoReloadRunnable<DestinationConfiguration, ReplicationQueue> autoReloadRunnable =
        new AutoReloadRunnable<>(
            newDestinationsCollections(replicationFileBasedConfig),
            replicationFileBasedConfig,
            sitePaths,
            pluginDataPath,
            eventBus,
            Providers.of(replicationQueueMock));
    return new AutoReloadConfigDecorator<>(
        "replication", workQueueMock, replicationFileBasedConfig, autoReloadRunnable, eventBus);
  }

  private ReplicationFileBasedConfig newReplicationFileBasedConfig() {
    return new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
  }
}
