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

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.junit.Before;
import org.junit.Test;

public class AutoReloadRunnableTest {

  private SitePaths sitePaths;
  private DynamicSet<ReplicationConfigValidator> validators;
  private EventBus eventBus;
  private ReloadTrackerSubscriber onReloadSubscriber;
  private String pluginName;

  @Before
  public void setUp() throws IOException {
    Path tmp = Files.createTempFile(pluginName, "_site");
    Files.deleteIfExists(tmp);
    sitePaths = new SitePaths(tmp);
    validators = new DynamicSet<>();
    pluginName = "replication";
    eventBus = new EventBus();
    onReloadSubscriber = new ReloadTrackerSubscriber();
    eventBus.register(onReloadSubscriber);
  }

  @Test
  public void configurationIsReloadedWhenValidationSucceeds() {
    final TestValidConfigurationListener testConfigurationListener =
        new TestValidConfigurationListener();
    validators.add(pluginName, testConfigurationListener);

    attemptAutoReload(validators);

    assertThat(onReloadSubscriber.reloaded).isTrue();
  }

  @Test
  public void configurationIsNotReloadedValidationFails() {
    final TestInvalidConfigurationListener testConfigurationListener =
        new TestInvalidConfigurationListener();
    validators.add(pluginName, testConfigurationListener);

    attemptAutoReload(validators);

    assertThat(onReloadSubscriber.reloaded).isFalse();
  }

  @Test
  public void configurationIsNotReloadedWhenAtLeastOneValidationFails() {
    final TestValidConfigurationListener validListener = new TestValidConfigurationListener();
    final TestInvalidConfigurationListener invalidListener = new TestInvalidConfigurationListener();
    validators.add(pluginName, validListener);
    validators.add(pluginName, invalidListener);

    attemptAutoReload(validators);

    assertThat(onReloadSubscriber.reloaded).isFalse();
  }

  private void attemptAutoReload(DynamicSet<ReplicationConfigValidator> validators) {
    final AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            validators, newVersionConfig(), sitePaths, sitePaths.data_dir, eventBus);

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
    public void onReload(ReplicationFileBasedConfig newConfig) {
      reloaded = true;
    }
  }

  private static class TestValidConfigurationListener implements ReplicationConfigValidator {
    @Override
    public void validateConfig(ReplicationFileBasedConfig newConfig) {}
  }

  private static class TestInvalidConfigurationListener implements ReplicationConfigValidator {
    @Override
    public void validateConfig(ReplicationFileBasedConfig configurationChangeEvent)
        throws ConfigInvalidException {
      throw new ConfigInvalidException("expected test failure");
    }
  }
}
