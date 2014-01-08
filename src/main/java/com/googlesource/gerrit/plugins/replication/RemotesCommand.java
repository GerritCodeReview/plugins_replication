// Copyright (C) 2014 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.args4j.Option;

/** Output the replication remotes. */
@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@CommandMetaData(name = "remotes", description = "Output the replication remotes")
final class RemotesCommand extends SshCommand {
  @Option(name = "--url", metaVar = "PATTERN", usage = "pattern to match URL on")
  private String urlMatch;

  @Inject private Provider<ReplicationDestinations> destinations;

  @Override
  protected void run() throws Failure {
    boolean firstRemote = true;
    for (Destination cfg : destinations.get().getAll(FilterType.ALL)) {
      boolean firstUrl = true;
      for (URIish uri : cfg.getURIs(Project.nameKey("${name}"), urlMatch)) {
        if (firstUrl) {
          if (!firstRemote) {
            stdout.println("");
          }
          stdout.println("remote: " + cfg.getRemoteConfigName());
          firstUrl = false;
          firstRemote = false;
        }
        stdout.println("   url: " + uri);
      }
    }
    stdout.flush();
  }
}
