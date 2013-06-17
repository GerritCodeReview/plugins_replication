// Copyright (C) 2012 The Android Open Source Project
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

import org.slf4j.Logger;

public class WrappedLogger {

  private final Logger logger;

  public WrappedLogger(Logger logger) {
    this.logger = logger;
  }

  public void warn(String msg, ReplicationState... states) {
    stateWriteErr("Warning: " + msg, states);
    logger.warn(msg);
  }

  public void warn(String msg, Throwable t, ReplicationState... states) {
    stateWriteErr("Warning: " + msg, states);
    logger.warn(msg, t);
  }

  public void error(String msg, ReplicationState... states) {
    stateWriteErr("Error: " + msg, states);
    logger.error(msg);
  }

  public void error(String msg, Throwable t, ReplicationState... states) {
    stateWriteErr("Error: " + msg, states);
    logger.error(msg, t);
  }

  private void stateWriteErr(String msg, ReplicationState[] states) {
    for (ReplicationState rs : states) {
      if (rs != null) {
        rs.writeStdErr(msg);
      }
    }
  }
}
