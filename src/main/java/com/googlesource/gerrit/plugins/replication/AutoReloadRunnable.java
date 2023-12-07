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

import com.google.common.eventbus.EventBus;
import com.google.common.flogger.FluentLogger;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.List;

public class AutoReloadRunnable implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final EventBus eventBus;
  private final Provider<ObservableQueue> queueObserverProvider;
  private final ConfigParser configParser;
  private ReplicationConfig loadedConfig;
  private Provider<ConfigResourceBasedReplicationConfig> replicationConfigProvider;
  private String loadedConfigVersion;
  private String lastFailedConfigVersion;

  @Inject
  public AutoReloadRunnable(
      ConfigParser configParser,
      Provider<ConfigResourceBasedReplicationConfig> replicationConfigProvider,
      EventBus eventBus,
      Provider<ObservableQueue> queueObserverProvider) {
    this.replicationConfigProvider = replicationConfigProvider;
    this.loadedConfig = replicationConfigProvider.get();
    this.loadedConfigVersion = loadedConfig.getVersion();
    this.lastFailedConfigVersion = "";
    this.eventBus = eventBus;
    this.queueObserverProvider = queueObserverProvider;
    this.configParser = configParser;
  }

  @Override
  public synchronized void run() {
    String pendingConfigVersion = loadedConfig.getVersion();
    ObservableQueue queue = queueObserverProvider.get();
    if (pendingConfigVersion.equals(loadedConfigVersion)
        || pendingConfigVersion.equals(lastFailedConfigVersion)
        || !queue.isRunning()
        || queue.isReplaying()) {
      return;
    }

    reload();
  }

  synchronized void reload() {
    String pendingConfigVersion = loadedConfig.getVersion();
    try {
      ReplicationConfig newConfig = replicationConfigProvider.get();
      final List<RemoteConfiguration> newValidDestinations =
          configParser.parseRemotes(newConfig.getConfig());
      loadedConfig = newConfig;
      loadedConfigVersion = newConfig.getVersion();
      lastFailedConfigVersion = "";
      eventBus.post(newValidDestinations);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot reload replication configuration: keeping existing settings");
      lastFailedConfigVersion = pendingConfigVersion;
    }
  }
}
