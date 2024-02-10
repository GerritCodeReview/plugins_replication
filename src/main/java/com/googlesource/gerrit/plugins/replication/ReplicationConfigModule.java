// Copyright (C) 2023 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Scopes;
import com.google.inject.internal.UniqueAnnotations;
import com.googlesource.gerrit.plugins.replication.api.ConfigResource;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public class ReplicationConfigModule extends AbstractModule {

  private final SitePaths site;
  private final Path cfgPath;

  @Inject
  ReplicationConfigModule(SitePaths site) {
    this.site = site;
    this.cfgPath = site.etc_dir.resolve(CONFIG_NAME);
  }

  @Override
  protected void configure() {
    bind(com.googlesource.gerrit.plugins.replication.api.ConfigResource.class)
        .to(getConfigResourceClass());

    if (getReplicationConfig().getBoolean("gerrit", "autoReload", false)) {
      bind(com.googlesource.gerrit.plugins.replication.api.ReplicationConfig.class)
          .to(AutoReloadConfigDecorator.class)
          .in(Scopes.SINGLETON);
      bind(LifecycleListener.class)
          .annotatedWith(UniqueAnnotations.create())
          .to(AutoReloadConfigDecorator.class);
    } else {
      bind(ReplicationConfig.class).to(ReplicationConfigImpl.class).in(Scopes.SINGLETON);
    }
  }

  public FileBasedConfig getReplicationConfig() {
    File replicationConfigFile = cfgPath.toFile();
    FileBasedConfig config = new FileBasedConfig(replicationConfigFile, FS.DETECTED);
    try {
      config.load();
    } catch (IOException | ConfigInvalidException e) {
      throw new ProvisionException("Unable to load " + replicationConfigFile.getAbsolutePath(), e);
    }
    return config;
  }

  private Class<? extends ConfigResource> getConfigResourceClass() {
    if (Files.exists(site.etc_dir.resolve(CONFIG_DIR))) {
      return FanoutConfigResource.class;
    }
    return FileConfigResource.class;
  }
}
