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

import com.google.gerrit.common.Nullable;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.CommandProcessing;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.SshOutputCommand;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Singleton
class ReplicationStarter {
  private final PushAll.Factory pushFactory;
  private final ReplicationStateLogger stateLog;

  @Inject
  ReplicationStarter(PushAll.Factory pushFactory, ReplicationStateLogger stateLog) {
    this.pushFactory = pushFactory;
    this.stateLog = stateLog;
  }

  void start(
      @Nullable String urlMatch,
      Set<String> remotesToConsider,
      ReplicationFilter filter,
      boolean now,
      boolean wait,
      SshOutputCommand sink) {
    ReplicationState state = new ReplicationState(new CommandProcessing(sink));

    Future<?> future =
        pushFactory
            .create(urlMatch, remotesToConsider, filter, state, now)
            .schedule(0, TimeUnit.SECONDS);

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
          sink.writeStdErrSync("We are interrupted while waiting replication to complete");
        }
      } else {
        sink.writeStdOutSync("Nothing to replicate");
      }
    }
  }
}
