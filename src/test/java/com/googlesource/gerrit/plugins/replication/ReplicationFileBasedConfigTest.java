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
import static java.util.concurrent.TimeUnit.MINUTES;

import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
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

    ReplicationFileBasedConfig replicationConfig = newReplicationFileBasedConfig();
    List<Destination> destinations = replicationConfig.getDestinations(FilterType.ALL);
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

    ReplicationFileBasedConfig replicationConfig = newReplicationFileBasedConfig();
    List<Destination> destinations = replicationConfig.getDestinations(FilterType.ALL);
    assertThat(destinations).hasSize(2);

    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);
    assertThatIsDestination(destinations.get(1), remoteName2, remoteUrl2);
  }

  @Test
  public void shouldReturnDefaultSshTimeoutsWhenNotSet() throws Exception {
    ReplicationFileBasedConfig replicationConfig = newReplicationFileBasedConfig();
    assertThat(replicationConfig.getSshConnectionTimeout()).isEqualTo(MINUTES.toMillis(2));
    assertThat(replicationConfig.getSshCommandTimeout()).isEqualTo(0);
  }

  @Test
  public void shouldReturnConfiguredSshTimeouts() throws Exception {
    FileBasedConfig config = newReplicationConfig();
    config.setString("gerrit", null, "sshConnectionTimeout", "4 m");
    config.setString("gerrit", null, "sshCommandTimeout", "20 s");
    config.save();
    ReplicationFileBasedConfig replicationConfig = newReplicationFileBasedConfig();
    assertThat(replicationConfig.getSshConnectionTimeout()).isEqualTo(MINUTES.toMillis(4));
    assertThat(replicationConfig.getSshCommandTimeout()).isEqualTo(20);
  }

  private ReplicationFileBasedConfig newReplicationFileBasedConfig()
      throws ConfigInvalidException, IOException {
    ReplicationFileBasedConfig replicationConfig =
        new ReplicationFileBasedConfig(sitePaths, destinationFactoryMock, pluginDataPath);
    return replicationConfig;
  }
}
