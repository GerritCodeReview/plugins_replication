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

import static org.apache.http.HttpStatus.SC_NO_CONTENT;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.ServletCommandProcessing;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;

public class StartReplicationProcessing {

  public enum CommandType {
    ALL,
    PROJECT
  }

  private final PushAll.Factory pushFactory;

  @Inject private ReplicationStateLogger stateLogger;

  @Inject
  StartReplicationProcessing(PushAll.Factory pushFactory) {
    this.pushFactory = pushFactory;
  }

  @VisibleForTesting
  public void execute(
      StartReplicationRequest startReplicationRequest,
      HttpServletResponse response,
      ReplicationStateLogger stateLogger)
      throws IOException {
    this.stateLogger = stateLogger;
    execute(startReplicationRequest, response);
  }

  public void execute(StartReplicationRequest startReplicationRequest, HttpServletResponse response)
      throws IOException {
    CommandType cmd = startReplicationRequest.getCommand();
    ReplicationFilter projectFilter;

    switch (cmd) {
      case ALL:
        projectFilter = ReplicationFilter.all();
        break;

      case PROJECT:
        List<String> projectPatterns = new ArrayList<>();
        projectPatterns.add(startReplicationRequest.getProject());
        projectFilter = new ReplicationFilter(projectPatterns);
        break;

      default:
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        return;
    }

    response.setStatus(SC_NO_CONTENT);
    String urlMatch = startReplicationRequest.getUrl();
    ReplicationState state = new ReplicationState(new ServletCommandProcessing());

    Future<?> future =
        pushFactory
            .create(urlMatch, projectFilter, state, startReplicationRequest.isNow())
            .schedule(0, TimeUnit.SECONDS);

    // This pattern mirrors the processing in the ssh version of the command.
    if (startReplicationRequest.isWait()) {
      if (future != null) {
        try {
          future.get();
        } catch (InterruptedException e) {
          stateLogger.error(
              "Thread was interrupted while waiting for PushAll operation to finish", e, state);
          return;
        } catch (ExecutionException e) {
          stateLogger.error("An exception was thrown in PushAll operation", e, state);
          return;
        }
      }

      if (state.hasPushTask()) {
        try {
          state.waitForReplication();
        } catch (InterruptedException e) {
          response.sendError(
              HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "Thread was interrupted while waiting for replication to complete");
        }
      }
    }
  }
}
