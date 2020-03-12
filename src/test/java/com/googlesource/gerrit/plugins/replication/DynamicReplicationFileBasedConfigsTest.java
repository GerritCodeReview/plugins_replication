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

import static com.google.common.truth.Truth.assertThat;

import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Test;

public class DynamicReplicationFileBasedConfigsTest extends AbstractConfigTest {

  public DynamicReplicationFileBasedConfigsTest() throws IOException {
    super();
  }

  String remoteName1 = "foo";
  String remoteUrl1 = "ssh://git@git.somewhere.com/${name}";
  String remoteName2 = "bar";
  String remoteUrl2 = "ssh://git@git.elsewhere.com/${name}";

  @Test
  public void shouldLoadOneDestination() throws Exception {

    FileBasedConfig config = newDynamicReplicationConfig("dynamic.config");
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(
            newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
  }

  @Test
  public void shouldLoadTwoDestinations() throws Exception {
    FileBasedConfig config = newDynamicReplicationConfig("dynamic.config");
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(
            newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
    assertThatIsDestination(destinations.get(1), remoteName2, remoteUrl2);
  }

  @Test
  public void shouldLoadDestinationsFromMultipleFiles() throws Exception {

    FileBasedConfig config = newDynamicReplicationConfig("dynamic.config");
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.save();

    config = newDynamicReplicationConfig("dynamic2.config");
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(
            newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatIsDestination(destinations.get(1), remoteName1, remoteUrl1);
    assertThatIsDestination(destinations.get(0), remoteName2, remoteUrl2);
  }

  @Test
  public void shouldLoadDestinationsFromStaticAndDynamicFiles() throws Exception {
    FileBasedConfig config = newReplicationConfig();
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.save();

    config = newDynamicReplicationConfig("dynamic2.config");
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(
            newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
    assertThatIsDestination(destinations.get(1), remoteName2, remoteUrl2);
  }

  @Test
  public void shouldLoadDestinationsFromConfigFiles() throws Exception {

    FileBasedConfig config = newDynamicReplicationConfig("dynamic.config");
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.save();

    config = newDynamicReplicationConfig("dynamic2.yaml");
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(
            newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
  }

  @Test(expected = ConfigInvalidException.class)
  public void shouldExceptionWhenInvalidUrl() throws Exception {

    FileBasedConfig config = newDynamicReplicationConfig("dynamic.config");
    config.setString("remote", remoteName1, "url", "ssh://git@git.elsewhere.com/name");
    config.save();

    newDestinationsCollections(
        newReplicationFileBasedConfig(), newDynamicReplicationFileBasedConfig());
  }
}
