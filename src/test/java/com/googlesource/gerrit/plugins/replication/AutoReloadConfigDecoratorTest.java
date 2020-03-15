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
  ReplicationConfig replicationConfig;

  public AutoReloadConfigDecoratorTest() throws IOException {
    super();
  }

  @Override
  @Before
  public void setup() {
    super.setup();
    replicationConfig = newReplicationFileBasedConfig();
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig fileConfig = newReplicationConfig();
    fileConfig.setBoolean("gerrit", null, "autoReload", true);
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    fileConfig.setString("remote", remoteName1, "url", remoteUrl1);
    fileConfig.save();

    newAutoReloadConfig().start();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(replicationConfigProvider.get());
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteName2 = "bar";
    String remoteUrl2 = "ssh://git@git.bar.com/${name}";
    fileConfig.setString("remote", remoteName2, "url", remoteUrl2);
    fileConfig.save();
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
    FileBasedConfig fileConfig = newReplicationConfig();
    fileConfig.setBoolean("gerrit", null, "autoReload", false);
    fileConfig.setString("remote", remoteName1, "url", remoteUrl1);
    fileConfig.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(replicationConfigProvider.get());
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    fileConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    fileConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinationsCollections.getAll(FilterType.ALL)).isEqualTo(destinations);
  }

  private AutoReloadConfigDecorator newAutoReloadConfig() throws ConfigInvalidException {
    AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            replicationConfigParser,
            replicationConfigProvider,
            eventBus,
            Providers.of(replicationQueueMock));
    return new AutoReloadConfigDecorator(
        "replication", workQueueMock, replicationConfigProvider, autoReloadRunnable, eventBus);
  }

  private ReplicationConfig newReplicationFileBasedConfig() {
    return replicationConfigProvider.get();
  }
}
