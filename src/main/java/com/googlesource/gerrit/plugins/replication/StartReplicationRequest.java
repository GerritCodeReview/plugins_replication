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

import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.ServletCommandProcessing;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;

public class StartReplicationRequest {
  private static final String ALL = "all";
  private static final String PROJECT = "project";

  private PushAll.Factory pushFactory;

  @Inject private ReplicationStateLogger stateLogger;

  @Inject
  StartReplicationRequest(PushAll.Factory pushFactory) {
    this.pushFactory = pushFactory;
  }

  public void execute(
      StartCommandRequest startCommandRequest,
      HttpServletResponse response,
      ReplicationStateLogger stateLogger)
      throws IOException {
    this.stateLogger = stateLogger;
    execute(startCommandRequest, response);
  }

  public void execute(StartCommandRequest startCommandRequest, HttpServletResponse response)
      throws IOException {
    String cmd = startCommandRequest.getCommand();
    ReplicationFilter projectFilter;

    switch (cmd) {
      case ALL:
        projectFilter = ReplicationFilter.all();
        break;

      case PROJECT:
        List<String> projectPatterns = new ArrayList<>();
        projectPatterns.add(startCommandRequest.getProject());
        projectFilter = new ReplicationFilter(projectPatterns);
        break;

      default:
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        return;
    }

    response.setStatus(SC_NO_CONTENT);
    String urlMatch = startCommandRequest.getUrl();
    ReplicationState state = new ReplicationState(new ServletCommandProcessing());

    Future<?> future =
        pushFactory
            .create(urlMatch, projectFilter, state, startCommandRequest.isNow())
            .schedule(0, TimeUnit.SECONDS);

    // This pattern mirrors the processing in the ssh version of the command.

    if (startCommandRequest.isWait()) {
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
