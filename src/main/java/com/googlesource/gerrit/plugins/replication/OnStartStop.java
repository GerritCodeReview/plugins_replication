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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationState.Factory;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OnStartStop implements LifecycleListener {
  private final AtomicReference<Future<?>> pushAllFuture;
  private final ServerInformation srvInfo;
  private final PushAll.Factory pushAll;
  private final ReplicationConfig config;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Factory replicationStateFactory;

  @Inject
  protected OnStartStop(
      ServerInformation srvInfo,
      PushAll.Factory pushAll,
      ReplicationConfig config,
      DynamicItem<EventDispatcher> eventDispatcher,
      ReplicationState.Factory replicationStateFactory) {
    this.srvInfo = srvInfo;
    this.pushAll = pushAll;
    this.config = config;
    this.eventDispatcher = eventDispatcher;
    this.replicationStateFactory = replicationStateFactory;
    this.pushAllFuture = Atomics.newReference();
  }

  @Override
  public void start() {
    if (srvInfo.getState() == ServerInformation.State.STARTUP
        && config.isReplicateAllOnPluginStart()) {
      ReplicationState state =
          replicationStateFactory.create(new GitUpdateProcessing(eventDispatcher.get()));
      pushAllFuture.set(
          pushAll
              .create(null, ReplicationFilter.all(), state, false)
              .schedule(30, TimeUnit.SECONDS));
    }
  }

  @Override
  public void stop() {
    Future<?> f = pushAllFuture.getAndSet(null);
    if (f != null) {
      f.cancel(true);
    }
  }
}
