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
  private final StartReplicationRequest startReplicationRequest;

  @Inject
  EventRestApiServlet(StartReplicationRequest startReplicationRequest) {
    this.startReplicationRequest = startReplicationRequest;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    StartCommandMsg startCommandMsg;
    BufferedReader reader = req.getReader();
    Gson gson = new Gson();

    try {
      startCommandMsg = gson.fromJson(reader, StartCommandMsg.class);
    } catch (JsonParseException e) {
      repLog.error("Problem parsing the message " + e.getLocalizedMessage());
      res.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    repLog.info(
        "Received command="
            + startCommandMsg.getCommand()
            + ", project="
            + startCommandMsg.getProject()
            + ", url="
            + startCommandMsg.getUrl()
            + ", now="
            + startCommandMsg.isNow()
            + ", wait="
            + startCommandMsg.isWait());
    String cmd = startCommandMsg.getCommand();
    if (cmd == null) {
      res.sendError(HttpServletResponse.SC_BAD_REQUEST, Constants.SC_BAD_REQUEST_MSG);
      return;
    }
    startReplicationRequest.execute(startCommandMsg, res);
  }
}
