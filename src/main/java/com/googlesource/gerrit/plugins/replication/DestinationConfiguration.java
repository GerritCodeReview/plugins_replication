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

import com.google.common.base.MoreObjects;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;

class DestinationConfiguration {
  private final int delay;
  private final int retryDelay;
  private final int lockErrorMaxRetries;
  private final String[] adminUrls;
  private final int poolThreads;
  private final boolean createMissingRepos;
  private final boolean replicatePermissions;
  private final boolean replicateProjectDeletions;
  private final String remoteNameStyle;
  private final String[] urls;
  private final String[] projects;
  private final String[] authGroupNames;
  private final RemoteConfig remoteConfig;

  DestinationConfiguration(RemoteConfig remoteConfig, Config cfg) {
    this.remoteConfig = remoteConfig;
    String name = remoteConfig.getName();
    urls = cfg.getStringList("remote", name, "url");
    delay = Math.max(0, getInt(remoteConfig, cfg, "replicationdelay", 15));
    projects = cfg.getStringList("remote", name, "projects");
    adminUrls = cfg.getStringList("remote", name, "adminUrl");
    retryDelay = Math.max(0, getInt(remoteConfig, cfg, "replicationretry", 1));
    poolThreads = Math.max(0, getInt(remoteConfig, cfg, "threads", 1));
    authGroupNames = cfg.getStringList("remote", name, "authGroup");
    lockErrorMaxRetries = cfg.getInt("replication", "lockErrorMaxRetries", 0);

    createMissingRepos =
        cfg.getBoolean("remote", name, "createMissingRepositories", true);
    replicatePermissions =
        cfg.getBoolean("remote", name, "replicatePermissions", true);
    replicateProjectDeletions =
        cfg.getBoolean("remote", name, "replicateProjectDeletions", false);
    remoteNameStyle = MoreObjects.firstNonNull(
        cfg.getString("remote", name, "remoteNameStyle"), "slash");
  }

  public int getDelay() {
    return delay;
  }

  public int getRetryDelay() {
    return retryDelay;
  }

  public int getPoolThreads() {
    return poolThreads;
  }

  public int getLockErrorMaxRetries() {
    return lockErrorMaxRetries;
  }

  public String[] getUrls() {
    return urls;
  }

  public String[] getAdminUrls() {
    return adminUrls;
  }

  public String[] getProjects() {
    return projects;
  }

  public String[] getAuthGroupNames() {
    return authGroupNames;
  }

  public String getRemoteNameStyle() {
    return remoteNameStyle;
  }

  public boolean replicatePermissions() {
    return replicatePermissions;
  }

  public boolean createMissingRepos() {
    return createMissingRepos;
  }

  public boolean replicateProjectDeletions() {
    return replicateProjectDeletions;
  }

  public RemoteConfig getRemoteConfig() {
    return remoteConfig;
  }

  private static int getInt(
      RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }
}
