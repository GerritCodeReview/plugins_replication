// Copyright (C) 2019 The Android Open Source Project
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

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.inject.Inject;

public class GlobalReplicationStateListener implements ReplicationStateListener {
  private final DynamicSet<ReplicationStateListener> stateListeners;

  @Inject
  GlobalReplicationStateListener(DynamicSet<ReplicationStateListener> stateListeners) {
    this.stateListeners = stateListeners;
  }

  @Override
  public void warn(String msg, ReplicationState... states) {
    for (ReplicationStateListener stateListener : stateListeners) {
      stateListener.warn(msg, states);
    }
  }

  @Override
  public void error(String msg, ReplicationState... states) {
    for (ReplicationStateListener stateListener : stateListeners) {
      stateListener.error(msg, states);
    }
  }

  @Override
  public void error(String msg, Throwable t, ReplicationState... states) {
    for (ReplicationStateListener stateListener : stateListeners) {
      stateListener.error(msg, t, states);
    }
  }

}
