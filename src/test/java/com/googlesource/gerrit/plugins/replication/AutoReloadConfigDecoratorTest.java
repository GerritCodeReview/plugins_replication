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

import com.google.inject.Provider;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Test;

public class AutoReloadConfigDecoratorTest extends AbstractConfigTest {
  ReplicationConfig replicationConfig;

  public AutoReloadConfigDecoratorTest() throws IOException {
    super();
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig fileConfig = newReplicationConfig();
    fileConfig.setBoolean("gerrit", null, "autoReload", true);
    String remoteName1 = "1_foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    fileConfig.setString("remote", remoteName1, "url", remoteUrl1);
    fileConfig.save();

    replicationConfig = newReplicationFileBasedConfig();

    newAutoReloadConfig(() -> newReplicationFileBasedConfig()).start();

    DestinationsCollection destinationsCollections = newDestinationsCollections(replicationConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteName2 = "2_bar";
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
  public void shouldAutoReloadFanoutReplicationConfigWhenConfigIsAdded() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig remoteConfig = newReplicationConfig("replication/" + remoteName1 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl1);
    remoteConfig.save();

    replicationConfig = new FanoutReplicationConfig(sitePaths, pluginDataPath);

    newAutoReloadConfig(
            () -> {
              try {
                return new FanoutReplicationConfig(sitePaths, pluginDataPath);
              } catch (IOException | ConfigInvalidException e) {
                throw new RuntimeException(e);
              }
            })
        .start();

    DestinationsCollection destinationsCollections = newDestinationsCollections(replicationConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteName2 = "foobar";
    String remoteUrl2 = "ssh://git@git.foobar.com/${name}";
    remoteConfig = newReplicationConfig("replication/" + remoteName2 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl2);
    remoteConfig.save();
    executorService.refreshCommand.run();

    destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);
  }

  @Test
  public void shouldAutoReloadFanoutReplicationConfigWhenConfigIsRemoved() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig remoteConfig = newReplicationConfig("replication/" + remoteName1 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl1);
    remoteConfig.save();

    String remoteName2 = "foobar";
    String remoteUrl2 = "ssh://git@git.foobar.com/${name}";
    remoteConfig = newReplicationConfig("replication/" + remoteName2 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl2);
    remoteConfig.save();

    replicationConfig = new FanoutReplicationConfig(sitePaths, pluginDataPath);

    newAutoReloadConfig(
            () -> {
              try {
                return new FanoutReplicationConfig(sitePaths, pluginDataPath);
              } catch (IOException | ConfigInvalidException e) {
                throw new RuntimeException(e);
              }
            })
        .start();

    DestinationsCollection destinationsCollections = newDestinationsCollections(replicationConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    assertThat(
            sitePaths.etc_dir.resolve("replication/" + remoteName2 + ".config").toFile().delete())
        .isTrue();

    executorService.refreshCommand.run();

    destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
  }

  @Test
  public void shouldAutoReloadFanoutReplicationConfigWhenConfigIsModified() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig remoteConfig = newReplicationConfig("replication/" + remoteName1 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl1);
    remoteConfig.save();

    String remoteName2 = "bar";
    String remoteUrl2 = "ssh://git@git.bar.com/${name}";
    remoteConfig = newReplicationConfig("replication/" + remoteName2 + ".config");
    remoteConfig.setString("remote", null, "url", remoteUrl2);
    remoteConfig.save();

    replicationConfig = new FanoutReplicationConfig(sitePaths, pluginDataPath);

    newAutoReloadConfig(
            () -> {
              try {
                return new FanoutReplicationConfig(sitePaths, pluginDataPath);
              } catch (IOException | ConfigInvalidException e) {
                throw new RuntimeException(e);
              }
            })
        .start();

    DestinationsCollection destinationsCollections = newDestinationsCollections(replicationConfig);
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteUrl3 = "ssh://git@git.foobar.com/${name}";
    remoteConfig.setString("remote", null, "url", remoteUrl3);
    remoteConfig.save();

    executorService.refreshCommand.run();

    destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl3);
  }

  @Test
  public void shouldNotAutoReloadReplicationConfigIfDisabled() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig fileConfig = newReplicationConfig();
    fileConfig.setBoolean("gerrit", null, "autoReload", false);
    fileConfig.setString("remote", remoteName1, "url", remoteUrl1);
    fileConfig.save();

    replicationConfig = newReplicationFileBasedConfig();

    DestinationsCollection destinationsCollections = newDestinationsCollections(replicationConfig);
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

  private AutoReloadConfigDecorator newAutoReloadConfig(
      Supplier<ReplicationConfig> configSupplier) {
    AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            configParser,
            new Provider<ReplicationConfig>() {

              @Override
              public ReplicationConfig get() {
                return configSupplier.get();
              }
            },
            eventBus,
            Providers.of(replicationQueueMock));
    return new AutoReloadConfigDecorator(
        "replication",
        workQueueMock,
        newReplicationFileBasedConfig(),
        autoReloadRunnable,
        eventBus);
  }
}
