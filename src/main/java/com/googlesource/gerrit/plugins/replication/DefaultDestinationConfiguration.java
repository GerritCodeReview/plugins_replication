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

class DefaultDestinationConfiguration implements DestinationConfiguration {
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

  DefaultDestinationConfiguration(RemoteConfig rc, Config cfg) {
    String name = rc.getName();
    urls = cfg.getStringList("remote", rc.getName(), "url");
    delay = Math.max(0, getInt(rc, cfg, "replicationdelay", 15));
    projects = cfg.getStringList("remote", name, "projects");
    adminUrls = cfg.getStringList("remote", name, "adminUrl");
    retryDelay = Math.max(0, getInt(rc, cfg, "replicationretry", 1));
    poolThreads = Math.max(0, getInt(rc, cfg, "threads", 1));
    authGroupNames = cfg.getStringList("remote", name, "authGroup");
    lockErrorMaxRetries = cfg.getInt("replication", "lockErrorMaxRetries", 0);

    createMissingRepos =
        cfg.getBoolean("remote", name, "createMissingRepositories", true);
    replicatePermissions =
        cfg.getBoolean("remote", name, "replicatePermissions", true);
    replicateProjectDeletions =
        cfg.getBoolean("remote", name, "replicateProjectDeletions", false);
    remoteNameStyle =
        MoreObjects.firstNonNull(cfg.getString("remote", name, "remoteNameStyle"), "slash");
  }

  @Override
  public int getDelay() {
    return delay;
  }

  @Override
  public int getRetryDelay() {
    return retryDelay;
  }

  @Override
  public int getPoolThreads() {
    return poolThreads;
  }

  @Override
  public int getLockErrorMaxRetries() {
    return lockErrorMaxRetries;
  }

  @Override
  public String[] getUrls() {
    return urls;
  }

  @Override
  public String[] getAdminUrls() {
    return adminUrls;
  }

  @Override
  public String[] getProjects() {
    return projects;
  }

  @Override
  public String[] getAuthGroupNames() {
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

  @Override
  public boolean createMissingRepos() {
    return createMissingRepos;
  }

  @Override
  public boolean replicateProjectDeletions() {
    return replicateProjectDeletions;
  }

  private static int getInt(
      RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }
}
