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

import com.google.common.util.concurrent.Atomics;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.inject.Inject;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class OnStartStop implements LifecycleListener {
  private final AtomicReference<Future<?>> pushAllFuture;
  private final ServerInformation srvInfo;
  private final PushAll.Factory pushAll;
  private final ReplicationQueue queue;

  @Inject
  OnStartStop(
      ServerInformation srvInfo,
      PushAll.Factory pushAll,
      ReplicationQueue queue) {
    this.srvInfo = srvInfo;
    this.pushAll = pushAll;
    this.queue = queue;
    this.pushAllFuture = Atomics.newReference();
  }

  @Override
  public void start() {
    queue.start();

    if (srvInfo.getState() == ServerInformation.State.STARTUP
        && queue.replicateAllOnPluginStart) {
      ReplicationState state =
          new ReplicationState(ReplicationType.STARTUP);
      pushAllFuture.set(pushAll.create(null, state).schedule(30, TimeUnit.SECONDS));
    }
  }

  @Override
  public void stop() {
    Future<?> f = pushAllFuture.getAndSet(null);
    if (f != null) {
      f.cancel(true);
    }
    queue.stop();
  }
}
