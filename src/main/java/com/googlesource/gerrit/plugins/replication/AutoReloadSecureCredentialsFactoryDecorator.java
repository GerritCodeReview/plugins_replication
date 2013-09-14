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

import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class AutoReloadSecureCredentialsFactoryDecorator implements
    CredentialsFactory {
  private static final Logger LOG = LoggerFactory
      .getLogger(AutoReloadSecureCredentialsFactoryDecorator.class);

  private final AtomicReference<SecureCredentialsFactory> secureCredentialsFactory;
  private volatile long secureCredentialsFactoryLoadTs;
  private final SitePaths site;
  private ReplicationFileBasedConfig config;

  @Inject
  public AutoReloadSecureCredentialsFactoryDecorator(SitePaths site,
      ReplicationFileBasedConfig config) throws ConfigInvalidException,
      IOException {
    this.site = site;
    this.config = config;
    this.secureCredentialsFactory =
        new AtomicReference<SecureCredentialsFactory>(
            new SecureCredentialsFactory(site));
    this.secureCredentialsFactoryLoadTs = getSecureConfigLastEditTs();
  }

  private long getSecureConfigLastEditTs() {
    FileBasedConfig cfg = new FileBasedConfig(site.secure_config, FS.DETECTED);
    if (cfg.getFile().exists()) {
      return cfg.getFile().lastModified();
    } else {
      return 0L;
    }
  }

  @Override
  public SecureCredentialsProvider create(String remoteName) {
    if (needsReload()) {
      try {
        secureCredentialsFactory.compareAndSet(secureCredentialsFactory.get(),
            new SecureCredentialsFactory(site));
        secureCredentialsFactoryLoadTs = getSecureConfigLastEditTs();
        LOG.info("secure.config reloaded as it was updated on the file system");
      } catch (Exception e) {
        LOG.error("Unexpected error while trying to reload "
            + "secure.config: existing existing credentials", e);
      }
    }

    return secureCredentialsFactory.get().create(remoteName);
  }


  private boolean needsReload() {
    return config.getConfig().getBoolean("gerrit", "autoReload", false) &&
        getSecureConfigLastEditTs() != secureCredentialsFactoryLoadTs;
  }
}
