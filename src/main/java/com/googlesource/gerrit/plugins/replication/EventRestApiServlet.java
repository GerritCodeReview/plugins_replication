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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.extensions.annotations.Export;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@Export("/start")
@Singleton
public class EventRestApiServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final StartReplicationProcessing startReplicationProcessing;

  @Inject
  EventRestApiServlet(StartReplicationProcessing startReplicationProcessing) {
    this.startReplicationProcessing = startReplicationProcessing;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) {
    Optional<StartReplicationRequest> startReplicationRequest;

    try {
      startReplicationRequest = createStartReplicationRequest(req);
    } catch (IOException e) {
      repLog.error("Problem reading start request for replication: {}", e.getLocalizedMessage());
      sendErrorMessageResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    if (!startReplicationRequest.isPresent()) {
      sendErrorMessageResponse(res, HttpServletResponse.SC_BAD_REQUEST);
      return;
    }

    repLog.debug(
        "Received command={}, project={}, url={}, now={}, wait={}",
        startReplicationRequest.get().getCommand(),
        startReplicationRequest.get().getProject(),
        startReplicationRequest.get().getUrl(),
        startReplicationRequest.get().isNow(),
        startReplicationRequest.get().isWait());

    try {
      startReplicationProcessing.execute(startReplicationRequest.get(), res);
    } catch (IOException e) {
      repLog.error("Problem executing replication: {}", e.getLocalizedMessage());
      sendErrorMessageResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  private Optional<StartReplicationRequest> createStartReplicationRequest(
      HttpServletRequest request) throws IOException {
    StartReplicationRequest startReplicationRequest;
    Gson gson = new Gson();

    try {
      BufferedReader reader = request.getReader();
      startReplicationRequest = gson.fromJson(reader, StartReplicationRequest.class);
    } catch (JsonParseException e) {
      repLog.error(e.getLocalizedMessage());
      return Optional.empty();
    }

    // Validate command as it is mandatory. If set to project, ensure project is set as well
    // Remaining fields are optional
    String command = startReplicationRequest.getCommand();
    if ((command != null)
        && (command.matches("all")
            || (command.matches("project") && (startReplicationRequest.getProject() != null)))) {
      return Optional.of(startReplicationRequest);
    }
    return Optional.empty();
  }

  private void sendErrorMessageResponse(HttpServletResponse res, int code) {
    try {
      res.sendError(code);
    } catch (IOException e) {
      repLog.error(e.getLocalizedMessage());
    }
  }
}
