// Copyright (C) 2013 The Android Open Source Project
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
  private DynamicSet<ReplicationConfigListener> listeners;
  private String pluginName = "replication";

  @Before
  public void setUp() throws IOException {
    Path tmp = Files.createTempFile(pluginName, "_site");
    Files.deleteIfExists(tmp);
    sitePaths = new SitePaths(tmp);
    listeners = new DynamicSet<>();
  }

  @Test
  public void onReloadIsCalledWhenConfigurationIsValid() {
    final TestValidConfigurationListener testConfigurationListener =
        new TestValidConfigurationListener();
    listeners.add(pluginName, testConfigurationListener);
    final AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(listeners, newVersionConfig(), sitePaths, sitePaths.data_dir);

    autoReloadRunnable.run();

    assertThat(testConfigurationListener.reloaded).isTrue();
  }

  @Test
  public void onReloadIsNotCalledWhenConfigurationIsInvalid() {
    final TestInvalidConfigurationListener testConfigurationListener =
        new TestInvalidConfigurationListener();
    listeners.add(pluginName, testConfigurationListener);
    final AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(listeners, newVersionConfig(), sitePaths, sitePaths.data_dir);

    autoReloadRunnable.run();

    assertThat(testConfigurationListener.reloaded).isFalse();
  }

  private ReplicationFileBasedConfig newVersionConfig() {
    return new ReplicationFileBasedConfig(sitePaths, sitePaths.data_dir) {
      @Override
      public String getVersion() {
        return String.format("%s", System.nanoTime());
      }
    };
  }

  private abstract static class TestConfigurationListener implements ReplicationConfigListener {
    public boolean reloaded = false;

    @Override
    @Subscribe
    public void onReload(ConfigurationChangeEvent configurationChangeEvent) {
      reloaded = true;
    }
  }

  private static class TestValidConfigurationListener extends TestConfigurationListener {
    @Override
    public void validateConfig(ConfigurationChangeEvent configurationChangeEvent) {}
  }

  private static class TestInvalidConfigurationListener extends TestConfigurationListener {
    @Override
    public void validateConfig(ConfigurationChangeEvent configurationChangeEvent)
        throws ConfigInvalidException {
      throw new ConfigInvalidException("expected test failure");
    }
  }
}
