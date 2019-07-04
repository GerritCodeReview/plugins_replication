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
public class AutoReloadConfigDecorator
    implements ReplicationConfig, ReplicationConfigListener, LifecycleListener {
  private static final long RELOAD_DELAY = 120;
  private static final long RELOAD_INTERVAL = 60;

  private ReplicationFileBasedConfig replicationConfig;

  private final ScheduledExecutorService autoReloadExecutor;
  private AutoReloadRunnable reloadRunner;
  private ScheduledFuture<?> scheduledReloadRunner;

  @Inject
  public AutoReloadConfigDecorator(
      @PluginName String pluginName,
      WorkQueue workQueue,
      ReplicationFileBasedConfig replicationConfig,
      AutoReloadRunnable reloadRunner) {
    this.replicationConfig = replicationConfig;
    this.autoReloadExecutor = workQueue.createQueue(1, pluginName + "_auto-reload-config");
    this.reloadRunner = reloadRunner;
  }

  @Override
  public synchronized boolean isReplicateAllOnPluginStart() {
    return replicationConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public synchronized boolean isDefaultForceUpdate() {
    return replicationConfig.isDefaultForceUpdate();
  }

  @Override
  public synchronized int getMaxRefsToLog() {
    return replicationConfig.getMaxRefsToLog();
  }

  @Override
  public Path getEventsDirectory() {
    return replicationConfig.getEventsDirectory();
  }

  @Override
  public synchronized void start() {
    scheduledReloadRunner =
        autoReloadExecutor.scheduleAtFixedRate(
            reloadRunner, RELOAD_DELAY, RELOAD_INTERVAL, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    scheduledReloadRunner.cancel(true);
  }

  @Override
  public String getVersion() {
    return replicationConfig.getVersion();
  }

  @Override
  @Subscribe
  public void onReload(ConfigurationChangeEvent configurationChangeEvent) {
    replicationConfig = configurationChangeEvent.newConfig();
  }

  @Override
  public void validateConfig(ConfigurationChangeEvent configurationChangeEvent) {}
}
