// Copyright (C) 2016 The Android Open Source Project
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

import static com.google.common.base.Suppliers.memoize;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.server.config.ConfigUtil;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;

public class DestinationConfiguration implements RemoteConfiguration {
  static final int DEFAULT_REPLICATION_DELAY = 15;
  static final int DEFAULT_RESCHEDULE_DELAY = 3;
  static final int DEFAULT_DRAIN_QUEUE_ATTEMPTS = 0;
  private static final int DEFAULT_SLOW_LATENCY_THRESHOLD_SECS = 900;

  private final int delay;
  private final int rescheduleDelay;
  private final int retryDelay;
  private final int drainQueueAttempts;
  private final int updateRefErrorMaxRetries;
  private final ImmutableList<String> adminUrls;
  private final int poolThreads;
  private final boolean createMissingRepos;
  private final boolean replicateNoteDbMetaRefs;
  private final boolean replicatePermissions;
  private final boolean replicateProjectDeletions;
  private final boolean replicateHiddenProjects;
  private final String remoteNameStyle;
  private final ImmutableList<String> urls;
  private final ImmutableList<String> projects;
  private final ImmutableList<String> authGroupNames;
  private final RemoteConfig remoteConfig;
  private final int maxRetries;
  private final int slowLatencyThreshold;
  private final Supplier<Integer> pushBatchSize;
  private final String username;
  private final String password;

  protected DestinationConfiguration(RemoteConfig remoteConfig, Config cfg) {
    this.remoteConfig = remoteConfig;
    String name = remoteConfig.getName();
    urls = ImmutableList.copyOf(cfg.getStringList("remote", name, "url"));
    delay = Math.max(0, getInt(remoteConfig, cfg, "replicationdelay", DEFAULT_REPLICATION_DELAY));
    rescheduleDelay =
        Math.max(3, getInt(remoteConfig, cfg, "rescheduledelay", DEFAULT_RESCHEDULE_DELAY));
    projects = ImmutableList.copyOf(cfg.getStringList("remote", name, "projects"));
    adminUrls = ImmutableList.copyOf(cfg.getStringList("remote", name, "adminUrl"));
    retryDelay = Math.max(0, getInt(remoteConfig, cfg, "replicationretry", 1));
    drainQueueAttempts =
        Math.max(0, getInt(remoteConfig, cfg, "drainQueueAttempts", DEFAULT_DRAIN_QUEUE_ATTEMPTS));
    poolThreads = Math.max(0, getInt(remoteConfig, cfg, "threads", 1));
    authGroupNames = ImmutableList.copyOf(cfg.getStringList("remote", name, "authGroup"));
    updateRefErrorMaxRetries =
        cfg.getInt(
            "replication",
            "updateRefErrorMaxRetries",
            cfg.getInt("replication", "lockErrorMaxRetries", 0));
    createMissingRepos = cfg.getBoolean("remote", name, "createMissingRepositories", true);
    replicateNoteDbMetaRefs = cfg.getBoolean("remote", name, "replicateNoteDbMetaRefs", true);
    replicatePermissions = cfg.getBoolean("remote", name, "replicatePermissions", true);
    replicateProjectDeletions = cfg.getBoolean("remote", name, "replicateProjectDeletions", false);
    replicateHiddenProjects = cfg.getBoolean("remote", name, "replicateHiddenProjects", false);
    remoteNameStyle =
        MoreObjects.firstNonNull(cfg.getString("remote", name, "remoteNameStyle"), "slash");
    maxRetries =
        getInt(
            remoteConfig, cfg, "replicationMaxRetries", cfg.getInt("replication", "maxRetries", 0));
    username = cfg.getString("remote", name, "username");
    password = cfg.getString("remote", name, "password");

    slowLatencyThreshold =
        (int)
            ConfigUtil.getTimeUnit(
                cfg,
                "remote",
                remoteConfig.getName(),
                "slowLatencyThreshold",
                DEFAULT_SLOW_LATENCY_THRESHOLD_SECS,
                TimeUnit.SECONDS);

    pushBatchSize =
        memoize(
            () -> {
              int configuredBatchSize =
                  Math.max(
                      0,
                      getInt(
                          remoteConfig,
                          cfg,
                          "pushBatchSize",
                          cfg.getInt("gerrit", "pushBatchSize", 0)));
              if (configuredBatchSize > 0) {
                int distributionInterval = cfg.getInt("replication", "distributionInterval", 0);
                if (distributionInterval > 0) {
                  repLog.atWarning().log(
                      "Push in batches cannot be turned on for remote (%s) when 'Cluster"
                          + " Replication' (replication.distributionInterval) is configured",
                      name);
                  return 0;
                }
                return configuredBatchSize;
              }
              return 0;
            });
  }

  @Override
  public int getDelay() {
    return delay;
  }

  @Override
  public int getRescheduleDelay() {
    return rescheduleDelay;
  }

  @Override
  public int getRetryDelay() {
    return retryDelay;
  }

  public int getDrainQueueAttempts() {
    return drainQueueAttempts;
  }

  public int getPoolThreads() {
    return poolThreads;
  }

  public int getUpdateRefErrorMaxRetries() {
    return updateRefErrorMaxRetries;
  }

  @Override
  public ImmutableList<String> getUrls() {
    return urls;
  }

  @Override
  public ImmutableList<String> getAdminUrls() {
    return adminUrls;
  }

  @Override
  public ImmutableList<String> getProjects() {
    return projects;
  }

  @Override
  public ImmutableList<String> getAuthGroupNames() {
    return authGroupNames;
  }

  @Override
  public String getRemoteNameStyle() {
    return remoteNameStyle;
  }

  @Override
  public boolean replicatePermissions() {
    return replicatePermissions;
  }

  public boolean createMissingRepos() {
    return createMissingRepos;
  }

  @Override
  public boolean replicateNoteDbMetaRefs() {
    return replicateNoteDbMetaRefs;
  }

  public boolean replicateProjectDeletions() {
    return replicateProjectDeletions;
  }

  public boolean replicateHiddenProjects() {
    return replicateHiddenProjects;
  }

  @Override
  public RemoteConfig getRemoteConfig() {
    return remoteConfig;
  }

  @Override
  public int getMaxRetries() {
    return maxRetries;
  }

  private static int getInt(RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }

  @Override
  public int getSlowLatencyThreshold() {
    return slowLatencyThreshold;
  }

  @Override
  public int getPushBatchSize() {
    return pushBatchSize.get();
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public String getPassword() {
    return password;
  }
}
