// Copyright (C) 2014 The Android Open Source Project
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

import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.gerrit.server.config.LogConfig;
import com.google.gerrit.server.util.PluginLogFile;
import com.google.gerrit.server.util.SystemLog;
import com.google.gerrit.util.logging.JsonLayout;
import com.google.gerrit.util.logging.JsonLogEntry;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class ReplicationLogFile extends PluginLogFile {

  @Inject
  public ReplicationLogFile(
      SystemLog systemLog, ServerInformation serverInfo, LogConfig config) {
    super(
        systemLog,
        serverInfo,
        ReplicationQueue.REPLICATION_LOG_NAME,
        PatternLayout.newBuilder().withPattern("[%d] %m%n").build(),
        new ReplicationJsonLayout(),
        config);
  }

  static class ReplicationJsonLayout extends JsonLayout {

    public ReplicationJsonLayout() {
      super(StandardCharsets.UTF_8);
    }

    @Override
    public JsonLogEntry toJsonLogEntry(LogEvent event) {
      return new ReplicationJsonLogEntry(event);
    }

    @SuppressWarnings("unused")
    private class ReplicationJsonLogEntry extends JsonLogEntry {
      public String timestamp;
      public String message;

      @SerializedName("@version")
      public final int version = 1;

      public ReplicationJsonLogEntry(LogEvent event) {
        timestamp = timestampFormatter.format(event.getTimeMillis());
        message = event.getMessage().getFormattedMessage();
      }
    }
  }
}
