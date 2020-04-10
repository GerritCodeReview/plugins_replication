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

import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Test;

public class ReplicationFileBasedConfigTest extends AbstractConfigTest {

  public ReplicationFileBasedConfigTest() throws IOException {
    super();
  }

  @Test
  public void shouldLoadOneDestination() throws Exception {
    String remoteName = "foo";
    String remoteUrl = "ssh://git@git.somewhere.com/${name}";
    FileBasedConfig config = newReplicationConfig();
    config.setString("remote", remoteName, "url", remoteUrl);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(newReplicationFileBasedConfig());
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);

    assertThatIsDestination(destinations.get(0), remoteName, remoteUrl);
  }

  @Test
  public void shouldLoadTwoDestinations() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.somewhere.com/${name}";
    String remoteName2 = "bar";
    String remoteUrl2 = "ssh://git@git.elsewhere.com/${name}";
    FileBasedConfig config = newReplicationConfig();
    config.setString("remote", remoteName1, "url", remoteUrl1);
    config.setString("remote", remoteName2, "url", remoteUrl2);
    config.save();

    DestinationsCollection destinationsCollections =
        newDestinationsCollections(newReplicationFileBasedConfig());
    destinationsCollections.startup(workQueueMock);
    List<Destination> destinations = destinationsCollections.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);
  }
}
