// Copyright (C) 2016 The Android Open Source Project
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

import com.google.common.base.Strings;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;

import org.kohsuke.args4j.Option;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name = "abort", description = "Abort replications")
final class AbortCommand extends SshCommand {
  @Option(name = "--remote", metaVar = "PATTERN", usage = "pattern to match remote name on")
  private String remote;

  @Option(name = "--dry-run",
      usage = "dry run. only show which destinations would be aborted")
  private boolean dryRun;

  @Inject
  private ReplicationConfig config;

  @Override
  protected void run() throws UnloggedFailure, Failure, Exception {
    if (Strings.isNullOrEmpty(remote)) {
      throw new UnloggedFailure("remote must be specified");
    }

    for (Destination d : config.getDestinations(FilterType.ALL)) {
      String name = d.getRemoteConfig().getName();
      if (name.contains(remote) || name.matches(remote)) {
        stdout.print(String.format("Aborting replication to %s\n",
            d.getRemoteConfig().getName()));
        if (!dryRun) {
          d.abortAll();
        }
      }
    }
  }
}
