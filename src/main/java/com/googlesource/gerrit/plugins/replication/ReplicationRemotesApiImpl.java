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

import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationRemotesApi;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.Config;

@Singleton
class ReplicationRemotesApiImpl implements ReplicationRemotesApi {

  private final SecureStore secureStore;
  private final MergedConfigResource mergedConfigResource;

  @Inject
  ReplicationRemotesApiImpl(SecureStore secureStore, MergedConfigResource mergedConfigResource) {
    this.secureStore = secureStore;
    this.mergedConfigResource = mergedConfigResource;
  }

  @Override
  public Config get(String... remoteNames) {
    Config replicationConfig = mergedConfigResource.getConfig();
    Config remoteConfig = new Config();
    for (String remoteName : remoteNames) {
      Set<String> configNames = replicationConfig.getNames("remote", remoteName);
      for (String configName : configNames) {
        String[] values = replicationConfig.getStringList("remote", remoteName, configName);
        if (values.length > 0) {
          remoteConfig.setStringList("remote", remoteName, configName, Arrays.asList(values));
        }
      }
    }
    return remoteConfig;
  }

  @Override
  public void update(Config remoteConfig) throws IOException {
    if (remoteConfig.getSubsections("remote").isEmpty()) {
      throw new IllegalArgumentException(
          "configuration update must have at least one 'remote' section");
    }

    SeparatedRemoteConfigs configs = onlyRemoteSectionsWithSeparatedCredentials(remoteConfig);
    persistRemotesCredentials(configs);

    mergedConfigResource.update(configs.remotes);
  }

  private SeparatedRemoteConfigs onlyRemoteSectionsWithSeparatedCredentials(Config configUpdates) {
    SeparatedRemoteConfigs configs = new SeparatedRemoteConfigs();
    for (String subSection : configUpdates.getSubsections("remote")) {
      for (String name : configUpdates.getNames("remote", subSection)) {
        List<String> values = List.of(configUpdates.getStringList("remote", subSection, name));
        if ("password".equals(name) || "username".equals(name)) {
          configs.credentials.setStringList("remote", subSection, name, values);
        } else {
          configs.remotes.setStringList("remote", subSection, name, values);
        }
      }
    }

    return configs;
  }

  private void persistRemotesCredentials(SeparatedRemoteConfigs configs) {
    for (String subSection : configs.credentials.getSubsections("remote")) {
      copyRemoteStringList(configs.credentials, subSection, "username");
      copyRemoteStringList(configs.credentials, subSection, "password");
    }
  }

  private void copyRemoteStringList(Config config, String subSection, String key) {
    secureStore.setList(
        "remote", subSection, key, List.of(config.getStringList("remote", subSection, key)));
  }

  private static class SeparatedRemoteConfigs {
    private final Config remotes = new Config();
    private final Config credentials = new Config();
  }
}
