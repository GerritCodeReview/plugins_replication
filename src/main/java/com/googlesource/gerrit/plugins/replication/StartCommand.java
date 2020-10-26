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
import java.util.Optional;
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

  private final Object lock = new Object();

  @Override
  protected void run() throws Failure {
    if (all && !projectPatterns.isEmpty()) {
      throw new UnloggedFailure(1, "error: cannot combine --all and PROJECT");
    }

    ReplicationState state = new ReplicationState(new CommandProcessing(this));
    Future<?> future = null;

    ReplicationFilter projectFilter;

    if (all) {
      projectFilter = ReplicationFilter.all();
    } else {
      projectFilter = new ReplicationFilter(projectPatterns);
    }

    future = pushFactory.create(urlMatch, projectFilter, state, now).schedule(0, TimeUnit.SECONDS);

    if (wait) {
      if (!state.hasPushTask()) {
        writeStdOutSync("Nothing to replicate");
      } else {
        Optional errorMsg = StartReplicationProcessing.handleWait(future, state);
        if (errorMsg.isPresent()) {
          stateLog.error(errorMsg.get().toString());
        }
      }
    }
  }

  public void writeStdOutSync(String message) {
    if (wait) {
      synchronized (lock) {
        stdout.println(message);
        stdout.flush();
      }
    }
  }

  public void writeStdErrSync(String message) {
    if (wait) {
      synchronized (lock) {
        stderr.println(message);
        stderr.flush();
      }
    }
  }
}
