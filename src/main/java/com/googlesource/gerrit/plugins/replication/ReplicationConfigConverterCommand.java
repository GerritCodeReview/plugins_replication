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

import com.google.common.collect.Lists;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.kohsuke.args4j.Option;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(
    name = "convert",
    description =
        "Extract remote sections from replication.config to separate files remote configuration files")
public class ReplicationConfigConverterCommand extends SshCommand {

  @Option(
      name = "--cleanup",
      usage = "Remove extracted remote sections from replication.config file")
  private boolean cleanupReplicationConfig;

  @Option(name = "--output", usage = "Directory where remote configuration files will be stored")
  private String output;

  private SitePaths site;

  @Inject
  public ReplicationConfigConverterCommand(SitePaths site) {
    this.site = site;
  }

  @Override
  protected void run() throws UnloggedFailure, Failure, Exception {
    Path outputDirPath =
        output == null ? site.etc_dir.resolve("replication") : createOutputPath(output);

    FileBasedConfig replicationConfigFile =
        new FileBasedConfig(site.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    replicationConfigFile.load();

    for (String remoteName : replicationConfigFile.getSubsections("remote")) {
      FileBasedConfig remoteConfigFile =
          new FileBasedConfig(
              outputDirPath.resolve(String.format("%s.config", remoteName)).toFile(), FS.DETECTED);
      for (String name : replicationConfigFile.getNames("remote", remoteName)) {
        remoteConfigFile.setStringList(
            "remote",
            null,
            name,
            Lists.newArrayList(replicationConfigFile.getStringList("remote", remoteName, name)));
      }
      remoteConfigFile.save();
      if (cleanupReplicationConfig) {
        replicationConfigFile.unsetSection("remote", remoteName);
      }
    }
    if (cleanupReplicationConfig) {
      replicationConfigFile.save();
    }
  }

  private Path createOutputPath(String output) throws UnloggedFailure {
    Path outputPath = Paths.get(output);
    if (outputPath.toFile().isFile()) {
      throw new UnloggedFailure(String.format("Output path '%s' must be a directory", output));
    }
    return outputPath;
  }
}
