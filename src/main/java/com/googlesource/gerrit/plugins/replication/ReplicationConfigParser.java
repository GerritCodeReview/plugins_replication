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

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import java.util.Collections;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class ReplicationConfigParser {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * parse the new replication.config
   *
   * @param replicationConfig new configuration to parse
   * @return List of parsed {@link RemoteConfiguration}
   * @throws ConfigInvalidException if the new configuration is not valid.
   */
  List<RemoteConfiguration> parse(ReplicationConfig replicationConfig)
      throws ConfigInvalidException {
    List<RemoteConfig> remoteConfigs = replicationConfig.getRemoteConfigs();

    if (remoteConfigs.isEmpty()) {
      logger.atWarning().log("Replication config does not exist or it's empty; not replicating");
      return Collections.emptyList();
    }

    boolean defaultForceUpdate =
        replicationConfig.getConfig().getBoolean("gerrit", "defaultForceUpdate", false);

    ImmutableList.Builder<RemoteConfiguration> confs = ImmutableList.builder();
    for (RemoteConfig c : remoteConfigs) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // If destination for push is not set assume equal to source.
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        c.addPushRefSpec(
            new RefSpec()
                .setSourceDestination("refs/*", "refs/*")
                .setForceUpdate(defaultForceUpdate));
      }

      DestinationConfiguration destinationConfiguration =
          new DestinationConfiguration(c, replicationConfig.getConfig());

      if (!destinationConfiguration.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format("remote.%s.url \"%s\" lacks ${name} placeholder", c.getName(), u));
          }
        }
      }

      confs.add(destinationConfiguration);
    }

    return confs.build();
  }
}
