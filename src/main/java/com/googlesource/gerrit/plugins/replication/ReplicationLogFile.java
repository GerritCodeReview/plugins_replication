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
import com.google.gerrit.server.util.PluginLogFile;
import com.google.gerrit.server.util.SystemLog;
import com.google.inject.Inject;
import java.io.Serializable;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.layout.PatternLayout;

public class ReplicationLogFile extends PluginLogFile {

  @Inject
  public ReplicationLogFile(SystemLog systemLog, ServerInformation serverInfo) {
    super(
        systemLog,
        serverInfo,
        ReplicationQueue.REPLICATION_LOG_NAME,
        PatternLayout.newBuilder()
            .withPattern("[%d] %m%n")
            .withPatternSelector(null)
            .withConfiguration(null)
            .withRegexReplacement(null)
            .withCharset(null)
            .withAlwaysWriteExceptions(false)
            .withNoConsoleNoAnsi(false)
            .withHeader(null)
            .withFooter(null)
            .build());
  }
}
