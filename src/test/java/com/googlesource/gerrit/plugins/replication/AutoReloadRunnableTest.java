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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.util.Providers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.Before;
import org.junit.Test;

public class AutoReloadRunnableTest {

  private SitePaths sitePaths;
  private EventBus eventBus;
  private ReloadTrackerSubscriber onReloadSubscriber;
  private String pluginName;
  private ReplicationQueue replicationQueueMock;

  @Before
  public void setUp() throws IOException {
    Path tmp = Files.createTempFile(pluginName, "_site");
    Files.deleteIfExists(tmp);
    sitePaths = new SitePaths(tmp);
    pluginName = "replication";
    eventBus = new EventBus();
    onReloadSubscriber = new ReloadTrackerSubscriber();
    eventBus.register(onReloadSubscriber);

    replicationQueueMock = mock(ReplicationQueue.class);
    when(replicationQueueMock.isRunning()).thenReturn(Boolean.TRUE);
  }

  @Test
  public void configurationIsReloadedWhenValidationSucceeds() {
    final DynamicItem<ReplicationConfigValidator> validator =
        DynamicItem.itemOf(ReplicationConfigValidator.class, new TestValidConfigurationListener());

    attemptAutoReload(validator);

    assertThat(onReloadSubscriber.reloaded).isTrue();
  }

  @Test
  public void configurationIsNotReloadedWhenValidationFails() {
    final DynamicItem<ReplicationConfigValidator> validator =
        DynamicItem.itemOf(
            ReplicationConfigValidator.class, new TestInvalidConfigurationListener());

    attemptAutoReload(validator);

    assertThat(onReloadSubscriber.reloaded).isFalse();
  }

  private void attemptAutoReload(DynamicItem<ReplicationConfigValidator> validator) {
    final AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            validator,
            newVersionConfig(),
            sitePaths,
            sitePaths.data_dir,
            eventBus,
            Providers.of(replicationQueueMock));

    autoReloadRunnable.run();
  }

  private ReplicationFileBasedConfig newVersionConfig() {
    return new ReplicationFileBasedConfig(sitePaths, sitePaths.data_dir) {
      @Override
      public String getVersion() {
        return String.format("%s", System.nanoTime());
      }
    };
  }

  private static class ReloadTrackerSubscriber {
    public boolean reloaded = false;

    @Subscribe
    public void onReload(List<DestinationConfiguration> destinationConfigurations) {
      reloaded = true;
    }
  }

  private static class TestValidConfigurationListener implements ReplicationConfigValidator {
    @Override
    public List<DestinationConfiguration> validateConfig(ReplicationFileBasedConfig newConfig) {
      return Collections.emptyList();
    }
  }

  private static class TestInvalidConfigurationListener implements ReplicationConfigValidator {
    @Override
    public List<DestinationConfiguration> validateConfig(
        ReplicationFileBasedConfig configurationChangeEvent) throws ConfigInvalidException {
      throw new ConfigInvalidException("expected test failure");
    }
  }
}
