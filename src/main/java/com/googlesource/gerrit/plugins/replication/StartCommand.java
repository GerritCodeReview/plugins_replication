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

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RequiresCapability(GlobalCapability.START_REPLICATION)
@CommandMetaData(name="start", descr="Start replication for specific project or all projects")
final class StartCommand extends SshCommand {
  private static final Logger log = LoggerFactory.getLogger(StartCommand.class);
  private static final WrappedLogger wrappedLog = new WrappedLogger(log);
  @Option(name = "--all", usage = "push all known projects")
  private boolean all;

  @Option(name = "--url", metaVar = "PATTERN", usage = "pattern to match URL on")
  private String urlMatch;

  @Option(name = "--wait",
      usage = "wait for replication to finish before exiting")
  private boolean wait;

  @Argument(index = 0, multiValued = true, metaVar = "PROJECT", usage = "project name")
  private List<String> projectNames = new ArrayList<String>(2);

  @Inject
  private PushAll.Factory pushAllFactory;

  @Inject
  private ReplicationQueue replication;

  @Inject
  private ProjectCache projectCache;

  @Override
  protected void run() throws Failure {
    if (all && projectNames.size() > 0) {
      throw new UnloggedFailure(1, "error: cannot combine --all and PROJECT");
    }

    ReplicationState state =
        new ReplicationState(ReplicationType.COMMAND, this);
    Future<?> future = null;
    if (all) {
      future = pushAllFactory.create(urlMatch, state).schedule(0, TimeUnit.SECONDS);
    } else {
      for (String name : projectNames) {
        Project.NameKey key = new Project.NameKey(name);
        if (projectCache.get(key) != null) {
          replication.scheduleFullSync(key, urlMatch, state);
        } else {
          writeStdErrSync("error: '" + name + "': not a Gerrit project");
        }
      }
      state.markAllPushTasksScheduled();
    }

    if (wait) {
      if (future != null) {
        try {
          future.get();
        } catch (InterruptedException e) {
          wrappedLog.error("Thread was interrupted while waiting for PushAll operation to finish", e, state);
          return;
        } catch (ExecutionException e) {
          wrappedLog.error("An exception was thrown in PushAll operation", e, state);
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

  public void writeStdOutSync(final String message) {
    if (wait) {
      synchronized (stdout) {
        stdout.println(message);
        stdout.flush();
      }
    }
  }

  public void writeStdErrSync(final String message) {
    if (wait) {
      synchronized (stderr) {
        stderr.println(message);
        stderr.flush();
      }
    }
  }
}
