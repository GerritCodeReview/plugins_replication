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

import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.io.MoreFiles;
import com.google.gerrit.server.config.SitePaths;
import com.googlesource.gerrit.plugins.replication.api.ConfigResource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FileConfigResourceTest {
  private static final String VALUE_KEY = "value";
  private static final String INITIAL_KEY = "initial";
  private static final String UPDATABLE_KEY = "updatable";

  private Path testDir;
  private SitePaths sitePaths;
  private ConfigResource configResource;

  @Before
  public void setUp() throws Exception {
    testDir = Files.createTempDirectory("fileConfigResourceTest");
    sitePaths = new SitePaths(testDir);
    configResource = newFileConfigResource();
  }

  @After
  public void tearDown() throws Exception {
    MoreFiles.deleteRecursively(testDir, ALLOW_INSECURE);
  }

  @Test
  public void updateEmptyFile() throws Exception {
    Config configUpdate = newConfigUpdate();

    Config beforeUpdate = configResource.getConfig();
    assertThat(beforeUpdate.getSections()).isEmpty();

    configResource.update(configUpdate);
    Config updatedConfig = newFileConfigResource().getConfig();

    assertConfigUpdate(updatedConfig);
  }

  @Test
  public void appendOptionToConfig() throws Exception {
    FileBasedConfig rawConfig = getRawReplicationConfig();
    rawConfig.setInt(INITIAL_KEY, null, VALUE_KEY, 10);
    rawConfig.save();
    configResource = newFileConfigResource();
    Config configUpdate = newConfigUpdate();

    configResource.update(configUpdate);
    Config updatedConfig = configResource.getConfig();

    assertConfigUpdate(updatedConfig);
    assertThat(updatedConfig.getInt(INITIAL_KEY, null, VALUE_KEY, -1)).isEqualTo(10);
  }

  @Test
  public void updateExistingOption() throws Exception {
    int expectedValue = 20;
    Config configUpdate = new Config();
    configUpdate.setInt(UPDATABLE_KEY, null, VALUE_KEY, expectedValue);
    FileBasedConfig rawConfig = getRawReplicationConfig(newConfigUpdate());
    rawConfig.save();

    configResource.update(configUpdate);
    Config updatedConfig = configResource.getConfig();

    assertConfigUpdate(updatedConfig, expectedValue);
  }

  private FileConfigResource newFileConfigResource() {
    return new FileConfigResource(sitePaths);
  }

  private Config newConfigUpdate() {
    Config configUpdate = new Config();
    configUpdate.setInt(UPDATABLE_KEY, null, VALUE_KEY, 1);
    return configUpdate;
  }

  private void assertConfigUpdate(Config config) {
    assertConfigUpdate(config, 1);
  }

  private void assertConfigUpdate(Config config, int expectedValue) {
    assertThat(config.getInt(UPDATABLE_KEY, null, VALUE_KEY, -1)).isEqualTo(expectedValue);
  }

  private FileBasedConfig getRawReplicationConfig() {
    return getRawReplicationConfig(new Config());
  }

  private FileBasedConfig getRawReplicationConfig(Config base) {
    Path configPath = sitePaths.etc_dir.resolve(FileConfigResource.CONFIG_NAME);
    return new FileBasedConfig(base, configPath.toFile(), FS.DETECTED);
  }
}
