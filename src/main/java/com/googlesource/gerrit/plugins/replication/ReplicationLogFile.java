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
import com.google.gerrit.server.util.SystemLog;
import com.google.inject.Inject;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

public class ReplicationLogFile implements LifecycleListener {

  private final SystemLog systemLog;

  @Inject
  public ReplicationLogFile(final SystemLog systemLog) {
    this.systemLog = systemLog;
  }

  @Override
  public void start() {
    Logger replicationLogger =
        LogManager.getLogger(ReplicationQueue.REPLICATION_LOG_NAME);
    replicationLogger.removeAllAppenders();
    replicationLogger.addAppender(systemLog.createAsyncAppender(
        replicationLogger.getName(), new PatternLayout("[%d] %m%n")));
    replicationLogger.setAdditivity(false);
  }

  @Override
  public void stop() {
    LogManager.getLogger(ReplicationQueue.repLog.getName()).removeAllAppenders();
  }
}
