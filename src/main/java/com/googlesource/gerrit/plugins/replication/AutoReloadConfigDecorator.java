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

import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import com.googlesource.gerrit.plugins.replication.RemoteSiteUser.Factory;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@Singleton
public class AutoReloadConfigDecorator implements ReplicationConfig {
  private static final Logger LOG = LoggerFactory
      .getLogger(AutoReloadConfigDecorator.class);
  private ReplicationFileBasedConfig currentConfig;
  private long currentConfigTs;
  private Injector injector;
  private SitePaths site;
  private Factory remoteSiteUserFactory;
  private PluginUser pluginUser;
  private SchemaFactory<ReviewDb> db;
  private GitRepositoryManager gitRepositoryManager;
  private GroupBackend groupBackend;
  private WorkQueue workQueue;

  @Inject
  public AutoReloadConfigDecorator(Injector injector, SitePaths site,
      Factory ruf, PluginUser pu, SchemaFactory<ReviewDb> db,
      GitRepositoryManager grm, GroupBackend gb) throws ConfigInvalidException,
      IOException {
    this.injector = injector;
    this.site = site;
    this.remoteSiteUserFactory = ruf;
    this.pluginUser = pu;
    this.db = db;
    this.gitRepositoryManager = grm;
    this.groupBackend = gb;
    this.currentConfig = loadConfig();
    this.currentConfigTs = currentConfig.getCfgPath().lastModified();
  }

  private ReplicationFileBasedConfig loadConfig()
      throws ConfigInvalidException, IOException {
    return new ReplicationFileBasedConfig(injector, site,
        remoteSiteUserFactory, pluginUser, db, gitRepositoryManager,
        groupBackend);
  }

  private synchronized boolean isAutoReload() {
    return currentConfig.getConfig().getBoolean("gerrit", "autoReload", false);
  }

  @Override
  public synchronized List<Destination> getDestinations() {
    reloadIfNeeded();
    return currentConfig.getDestinations();
  }

  private void reloadIfNeeded() {
    if (isAutoReload()
        && currentConfig.getCfgPath().lastModified() > currentConfigTs) {
      try {
        ReplicationFileBasedConfig newConfig = loadConfig();
        int discarded = currentConfig.shutdown();
        if(workQueue != null) {
          newConfig.startup(workQueue);
        }

        this.currentConfig = newConfig;
        this.currentConfigTs = currentConfig.getCfgPath().lastModified();

        LOG.info("Configuration reloaded: "
            + currentConfig.getDestinations().size() + " destinations, "
            + discarded + " replication events discarded");

      } catch (Exception e) {
        LOG.error(
            "Cannot reload replication configuration: keeping existing settings",
            e);
        return;
      }
    }
  }

  @Override
  public synchronized boolean isReplicateAllOnPluginStart() {
    return currentConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public synchronized boolean isEmpty() {
    return currentConfig.isEmpty();
  }

  @Override
  public synchronized int shutdown() {
    return currentConfig.shutdown();
  }

  @Override
  public synchronized void startup(WorkQueue workQueue) {
    this.workQueue = workQueue;
    currentConfig.startup(workQueue);
  }
}
