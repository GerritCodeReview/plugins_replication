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
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@Export("/start")
@Singleton
public class EventRestApiServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private static StartCommandRequest startCommandRequest = null;
  private final StartReplicationRequest startReplicationRequest;

  @Inject
  EventRestApiServlet(StartReplicationRequest startReplicationRequest) {
    this.startReplicationRequest = startReplicationRequest;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) {

    if (!setStartCommandRequest(req)) {
      try {
        res.sendError(HttpServletResponse.SC_BAD_REQUEST, Constants.SC_BAD_REQUEST_MSG);
      } catch (IOException ioException) {
        repLog.error("Problem sending servlet response {}", ioException.getLocalizedMessage());
      }
      return;
    }

    repLog.info(
        "Received command={}, project={}, url={}, now={}, wait={}",
        startCommandRequest.getCommand(),
        startCommandRequest.getProject(),
        startCommandRequest.getUrl(),
        startCommandRequest.isNow(),
        startCommandRequest.isWait());

    try {
      startReplicationRequest.execute(startCommandRequest, res);
    } catch (IOException e) {
      repLog.error("Problem executing replication {}", e.getLocalizedMessage());
    }
  }

  private static boolean setStartCommandRequest(HttpServletRequest request) {

    try {
      Gson gson = new Gson();
      BufferedReader reader = request.getReader();
      startCommandRequest = gson.fromJson(reader, StartCommandRequest.class);
    } catch (JsonParseException | IOException e) {
      return false;
    }

    // Validate command as it is mandatory. If set to project, ensure project is set as well
    // Remaining fields are optional

    String command = startCommandRequest.getCommand();
    if (command != null) {
      return (command.matches("all")
          || (command.matches("project") && (startCommandRequest.getProject() != null)));
    }
    return false;
  }
}
