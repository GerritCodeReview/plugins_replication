// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig, LifecycleListener {
  private static final long RELOAD_DELAY = 120;
  private static final long RELOAD_INTERVAL = 60;

  private ReplicationFileBasedConfig currentConfig;

  private final ScheduledExecutorService autoReloadExecutor;
  private ScheduledFuture<?> autoReloadRunnable;
  private AutoReloadRunnable reloadRunner;

  @Inject
  public AutoReloadConfigDecorator(
      @PluginName String pluginName,
      WorkQueue workQueue,
      ReplicationFileBasedConfig replicationConfig,
      AutoReloadRunnable reloadRunner,
      EventBus eventBus) {
    this.currentConfig = replicationConfig;
    this.autoReloadExecutor = workQueue.createQueue(1, pluginName + "_auto-reload-config");
    this.reloadRunner = reloadRunner;
    eventBus.register(this);
  }

  @VisibleForTesting
  public void forceReload() {
    reloadRunner.forceReload();
  }

  @Override
  public synchronized boolean isReplicateAllOnPluginStart() {
    return currentConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public synchronized boolean isDefaultForceUpdate() {
    return currentConfig.isDefaultForceUpdate();
  }

  @Override
  public synchronized int getMaxRefsToLog() {
    return currentConfig.getMaxRefsToLog();
  }

  @Override
  public Path getEventsDirectory() {
    return currentConfig.getEventsDirectory();
  }

  @Override
  public synchronized void start() {
    autoReloadRunnable =
        autoReloadExecutor.scheduleAtFixedRate(
            reloadRunner, RELOAD_DELAY, RELOAD_INTERVAL, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    if (autoReloadRunnable != null) {
      autoReloadRunnable.cancel(true);
      autoReloadRunnable = null;
    }
  }

  @Override
  public String getVersion() {
    return currentConfig.getVersion();
  }

  @Subscribe
  public void onReload(ReplicationFileBasedConfig newConfig) {
    currentConfig = newConfig;
  }

  @Override
  public synchronized int getSshConnectionTimeout() {
    return currentConfig.getSshConnectionTimeout();
  }

  @Override
  public synchronized int getSshCommandTimeout() {
    return currentConfig.getSshCommandTimeout();
  }
}
