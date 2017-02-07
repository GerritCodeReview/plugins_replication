// Copyright (C) 2015 The Android Open Source Project
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

/** Interface for notifying replication status updates. */
public interface ReplicationStateListener {

  /**
   * Notify a non-fatal replication error.
   *
   * <p>Replication states received a non-fatal error with an associated warning message.
   *
   * @param msg message description of the error
   * @param states replication states impacted
   */
  void warn(String msg, ReplicationState... states);

  /**
   * Notify a fatal replication error.
   *
   * <p>Replication states have received a fatal error and replication has failed.
   *
   * @param msg message description of the error
   * @param states replication states impacted
   */
  void error(String msg, ReplicationState... states);

  /**
   * Notify a fatal replication error with the associated exception.
   *
   * <p>Replication states have received a fatal exception and replication has failed.
   *
   * @param msg message description of the error
   * @param t exception that caused the replication to fail
   * @param states replication states impacted
   */
  void error(String msg, Throwable t, ReplicationState... states);
}
