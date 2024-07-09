// Copyright (C) 2020 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;

import com.google.common.io.MoreFiles;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig.FilterType;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;

public class FanoutConfigResourceTest extends AbstractConfigTest {

  public FanoutConfigResourceTest() throws IOException {
    super();
  }

  String remoteName1 = "foo";
  String remoteUrl1 = "ssh://git@git.somewhere.com/${name}";
  String remoteName2 = "bar";
  String remoteUrl2 = "ssh://git@git.elsewhere.com/${name}";

  @Before
  public void setupTests() throws Exception {
    FileBasedConfig config = newReplicationConfig();
    try {
      config.save();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    File replicationConfig = sitePaths.etc_dir.resolve(CONFIG_DIR).toFile();
    if (!replicationConfig.mkdir()) {
      throw new IOException(
          "Cannot create test replication config directory in: "
              + replicationConfig.toPath().toAbsolutePath());
    }
  }

  @Test
  public void shouldSkipRemoteConfigFromReplicationConfig() throws Exception {
    String remoteName = "foo";
    String remoteUrl = "ssh://git@git.somewhere.com/${name}";

    FileBasedConfig config = newReplicationConfig();
    config.setString("remote", remoteName, "url", remoteUrl);
    config.save();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName2, remoteUrl2);
  }

  @Test
  public void shouldLoadDestinationsFromMultipleFiles() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);
  }

  @Test
  public void shouldIgnoreDestinationsFromSubdirectories() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config = newRemoteConfig("subdirectory/" + remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
  }

  @Test
  public void shouldIgnoreNonConfigFiles() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config =
        new FileBasedConfig(
            sitePaths.etc_dir.resolve("replication/" + remoteName2 + ".yaml").toFile(),
            FS.DETECTED);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
  }

  @Test(expected = ConfigInvalidException.class)
  public void shouldThrowConfigInvalidExceptionWhenUrlIsMissingName() throws Exception {
    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", "ssh://git@git.elsewhere.com/name");
    config.save();

    newDestinationsCollections(new FanoutConfigResource(sitePaths));
  }

  @Test
  public void shouldIgnoreEmptyConfigFile() throws Exception {
    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(0);
  }

  @Test
  public void shouldIgnoreConfigWhenMoreThanOneRemoteInASingleFile() throws Exception {
    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(0);
  }

  @Test
  public void shouldIgnoreConfigRemoteSection() throws Exception {
    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("replication", null, "url", remoteUrl1);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(new FanoutConfigResource(sitePaths));
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(0);
  }

  @Test
  public void shouldReturnSameVersionWhenNoChanges() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);

    String version = objectUnderTest.getVersion();

    objectUnderTest = new FanoutConfigResource(sitePaths);

    assertThat(objectUnderTest.getVersion()).isEqualTo(version);
  }

  @Test
  public void shouldReturnNewVersionWhenConfigFileAdded() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);

    String version = objectUnderTest.getVersion();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    assertThat(objectUnderTest.getVersion()).isNotEqualTo(version);
  }

  @Test
  public void shouldReturnNewVersionWhenConfigFileIsModified() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);

    String version = objectUnderTest.getVersion();

    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    assertThat(objectUnderTest.getVersion()).isNotEqualTo(version);
  }

  @Test
  public void shouldReturnNewVersionWhenConfigFileRemoved() throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);

    String version = objectUnderTest.getVersion();
    assertThat(
            sitePaths.etc_dir.resolve("replication/" + remoteName2 + ".config").toFile().delete())
        .isTrue();

    assertThat(objectUnderTest.getVersion()).isNotEqualTo(version);
  }

  @Test
  public void shouldReturnReplicationConfigVersionWhenReplicationConfigDirectoryRemoved()
      throws Exception {

    FileBasedConfig config = newRemoteConfig(remoteName1);
    config.setString("remote", null, "url", remoteUrl1);
    config.save();

    config = newRemoteConfig(remoteName2);
    config.setString("remote", null, "url", remoteUrl2);
    config.save();

    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);

    String replicationConfigVersion =
        new ReplicationConfigImpl(
                MergedConfigResource.withBaseOnly(new FileConfigResource(sitePaths)),
                sitePaths,
                pluginDataPath)
            .getVersion();

    MoreFiles.deleteRecursively(sitePaths.etc_dir.resolve("replication"), ALLOW_INSECURE);

    assertThat(objectUnderTest.getVersion()).isEqualTo(replicationConfigVersion);
  }

  @Test
  public void shouldAddConfigOptionToMainConfig() throws Exception {
    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);
    Config update = new Config();
    update.setString("new", null, "value", "set");

    objectUnderTest.update(update);
    Config updatedConfig = objectUnderTest.getConfig();

    assertThat(updatedConfig.getString("new", null, "value")).isEqualTo("set");
  }

  @Test
  public void shouldUpdateConfigOptionInMainConfig() throws Exception {
    FileBasedConfig config = newReplicationConfig();
    config.setString("updatable", null, "value", "orig");
    config.save();
    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);
    Config update = new Config();
    update.setString("updatable", null, "value", "updated");

    objectUnderTest.update(update);
    Config updatedConfig = objectUnderTest.getConfig();

    assertThat(updatedConfig.getString("updatable", null, "value")).isEqualTo("updated");
  }

  @Test
  public void shouldAddNewRemoteFile() throws Exception {
    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);
    Config update = new Config();
    update.setString("remote", remoteName1, "url", remoteUrl1);

    objectUnderTest.update(update);

    assertThat(objectUnderTest.getConfig().getString("remote", remoteName1, "url"))
        .isEqualTo(remoteUrl1);
    Config actual = loadRemoteConfig(remoteName1);
    assertThat(actual.getString("remote", null, "url")).isEqualTo(remoteUrl1);
  }

  @Test
  public void shouldUpdateExistingRemote() throws Exception {
    FileBasedConfig rawRemoteConfig = newRemoteConfig(remoteName1);
    rawRemoteConfig.setString("remote", remoteName1, "url", remoteUrl1);
    rawRemoteConfig.save();
    FanoutConfigResource objectUnderTest = new FanoutConfigResource(sitePaths);
    Config update = new Config();
    update.setString("remote", remoteName1, "url", remoteUrl2);

    objectUnderTest.update(update);

    assertThat(objectUnderTest.getConfig().getString("remote", remoteName1, "url"))
        .isEqualTo(remoteUrl2);
    Config actual = loadRemoteConfig(remoteName1);
    assertThat(actual.getString("remote", null, "url")).isEqualTo(remoteUrl2);
  }

  protected FileBasedConfig newRemoteConfig(String configFileName) {
    return new FileBasedConfig(
        sitePaths.etc_dir.resolve("replication/" + configFileName + ".config").toFile(),
        FS.DETECTED);
  }

  private Config loadRemoteConfig(String siteName) throws Exception {
    FileBasedConfig config = newRemoteConfig(siteName);
    config.load();
    return config;
  }
}
