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

/**
 * Wrapper around a Logger that also logs out the replication state.
 * <p>
 * When logging replication errors it is useful to know the current
 * replication state.  This utility class wraps the methods from Logger
 * and logs additional information about the replication state to the
 * stderr console.
 */
public class ReplicationStateLogger implements ReplicationStateListener {

  private final Logger logger;

  public ReplicationStateLogger(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void warn(String msg, ReplicationState... states) {
    stateWriteErr("Warning: " + msg, states);
    logger.warn(msg);
  }

  @Override
  public void error(String msg, ReplicationState... states) {
    stateWriteErr("Error: " + msg, states);
    logger.error(msg);
  }

  @Override
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
