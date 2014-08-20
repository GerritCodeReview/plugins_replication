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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.gerrit.server.util.SystemLog;
import com.google.inject.Inject;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

public class ReplicationLogFile implements LifecycleListener {

  private final SystemLog systemLog;
  private final ServerInformation serverInfo;
  private static boolean started = false;

  @Inject
  public ReplicationLogFile(final SystemLog systemLog,
      ServerInformation serverInfo) {
    this.systemLog = systemLog;
    this.serverInfo = serverInfo;
  }

  @Override
  public void start() {
    if (!started) {
      Logger replicationLogger =
          LogManager.getLogger(ReplicationQueue.REPLICATION_LOG_NAME);
      replicationLogger.removeAllAppenders();
      replicationLogger.addAppender(systemLog.createAsyncAppender(
          replicationLogger.getName(), new PatternLayout("[%d] [%X{"
              + PushOne.ID_MDC_KEY + "}] %m%n")));
      replicationLogger.setAdditivity(false);
      started = true;
    }
  }

  @Override
  public void stop() {
    // stop is called when plugin is unloaded or when the server shutdown.
    // Only clean up when the server is shutting down to prevent issue when a
    // plugin is reloaded. The issue is that gerrit load the new plugin and then
    // unload the old one so because loggers are static, the unload of the old
    // plugin would remove the appenders just created by the new plugin.
    if (serverInfo.getState() == ServerInformation.State.SHUTDOWN) {
      LogManager.getLogger(ReplicationQueue.repLog.getName())
          .removeAllAppenders();
    }
  }
}
