// Copyright (C) 2009 The Android Open Source Project
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

import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.CommandProcessing;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@CommandMetaData(
    name = "start",
    description = "Start replication for specific project or all projects")
final class StartCommand extends SshCommand {
  @Inject private ReplicationStateLogger stateLog;

  @Option(name = "--all", usage = "push all known projects")
  private boolean all;

  @Option(name = "--url", metaVar = "PATTERN", usage = "pattern to match URL on")
  private String urlMatch;

  @Option(name = "--wait", usage = "wait for replication to finish before exiting")
  private boolean wait;

  @Option(name = "--now", usage = "start replication without waiting for replicationDelay")
  private boolean now;

  @Argument(index = 0, multiValued = true, metaVar = "PATTERN", usage = "project name pattern")
  private List<String> projectPatterns = new ArrayList<>(2);

  @Inject private PushAll.Factory pushFactory;

  @Inject private ReplicationState.Factory replicationStateFactory;

  @Override
  protected void run() throws Failure {
    if (all && projectPatterns.size() > 0) {
      throw new UnloggedFailure(1, "error: cannot combine --all and PROJECT");
    }

    ReplicationState state = replicationStateFactory.create(new CommandProcessing(this));
    Future<?> future = null;

    ReplicationFilter projectFilter;

    if (all) {
      projectFilter = ReplicationFilter.all();
    } else {
      projectFilter = new ReplicationFilter(projectPatterns);
    }

    future = pushFactory.create(urlMatch, projectFilter, state, now).schedule(0, TimeUnit.SECONDS);

    if (wait) {
      if (future != null) {
        try {
          future.get();
        } catch (InterruptedException e) {
          stateLog.error(
              "Thread was interrupted while waiting for PushAll operation to finish", e, state);
          return;
        } catch (ExecutionException e) {
          stateLog.error("An exception was thrown in PushAll operation", e, state);
          return;
        }
      }

      if (state.hasPushTask()) {
        try {
          state.waitForReplication();
        } catch (InterruptedException e) {
          writeStdErrSync("We are interrupted while waiting replication to complete");
        }
      } else {
        writeStdOutSync("Nothing to replicate");
      }
    }
  }

  public void writeStdOutSync(String message) {
    if (wait) {
      synchronized (stdout) {
        stdout.println(message);
        stdout.flush();
      }
    }
  }

  public void writeStdErrSync(String message) {
    if (wait) {
      synchronized (stderr) {
        stderr.println(message);
        stderr.flush();
      }
    }
  }
}
