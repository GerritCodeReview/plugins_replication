// Copyright (C) 2026 The Android Open Source Project
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
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ProjectRepairer.Action;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@CommandMetaData(name = "repair", description = "Repair a project on replication destinations")
final class RepairCommand extends SshCommand implements PushResultProcessing.SshOutputCommand {
  @Argument(index = 0, required = true, metaVar = "PROJECT", usage = "project name")
  private String projectName;

  @Option(
      name = "--url",
      metaVar = "SUBSTRING",
      usage = "substring URL must match (or * to match everything)")
  private String urlMatch;

  private final LinkedHashSet<Action> actions = new LinkedHashSet<>();

  @Option(
      name = "--copy-packs",
      usage = "rsync objects/pack files to SSH destinations before triggering replication")
  void setCopyPacks(@SuppressWarnings("unused") boolean arg) {
    actions.add(Action.COPY_PACKS);
  }

  @Option(name = "--full", usage = "run all supported repair actions (default)")
  void setFull(@SuppressWarnings("unused") boolean arg) {
    actions.clear();
    actions.addAll(Action.all());
  }

  @Inject private ProjectCache projectCache;
  @Inject private ReplicationDestinations destinations;
  @Inject private ReplicationStarter replicationStarter;
  @Inject private ProjectRepairer projectRepairer;

  private final Object outputLock = new Object();

  @Override
  protected void run() throws Failure {
    Project.NameKey project = Project.nameKey(projectName);
    try {
      if (projectCache.get(project).isEmpty()) {
        throw die("Project with name " + projectName + " not found.");
      }
    } catch (StorageException e) {
      throw die(e);
    }

    Set<URIish> failedUris = repair(project, repairActions());
    if (!failedUris.isEmpty()) {
      throw new UnloggedFailure(1, "Repair failed for " + failedUris.size() + " destination(s)");
    }
  }

  private Collection<Action> repairActions() {
    return actions.isEmpty() ? Action.all() : actions;
  }

  private Set<URIish> repair(Project.NameKey project, Collection<Action> actions) throws Failure {
    Set<URIish> copyTargets = new HashSet<>();
    Collection<URIish> destUris =
        destinations
            .getURIs(Optional.empty(), project, ReplicationConfig.FilterType.ALL, urlMatch)
            .values();
    for (URIish uri : destUris) {
      if (!ProjectRepairer.canCopy(uri)) {
        writeStdErrSync(
            "Warning: skipping " + uri + " as repair only supports plain SSH destinations");
        continue;
      }
      copyTargets.add(uri);
    }

    if (copyTargets.isEmpty()) {
      throw die("No matching destinations found");
    }

    Set<URIish> failedUris = new HashSet<>();
    OutputStream out = getFlushingOutputStream();
    for (URIish uri : copyTargets) {
      writeStdOutSync("\nRepairing " + uri + " ...");
      if (projectRepairer.repair(project, uri, out, actions)) {
        writeStdOutSync(
            "\nRunning replication start for " + project.get() + " to " + uri.toString() + " ...");
        replicationStarter.start(
            uri.toString(),
            PushOne.ALL_REFS,
            Set.of(),
            new ReplicationFilter(List.of(project.get()), Collections.emptyList()),
            /* now= */ true,
            /* wait= */ true,
            this);
      } else {
        failedUris.add(uri);
      }
    }
    return failedUris;
  }

  private OutputStream getFlushingOutputStream() {
    return new OutputStream() {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        out.flush();
      }

      @Override
      public void write(int b) throws IOException {
        out.write(b);
        out.flush();
      }
    };
  }

  @Override
  public void writeStdOutSync(String message) {
    synchronized (outputLock) {
      stdout.println(message);
      stdout.flush();
    }
  }

  @Override
  public void writeStdErrSync(String message) {
    synchronized (outputLock) {
      stderr.println(message);
      stderr.flush();
    }
  }
}
