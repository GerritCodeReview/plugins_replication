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

import static com.google.gerrit.common.FileUtil.lastModified;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.CredentialsProvider;

import com.google.gerrit.server.securestore.DefaultSecureStore;
import com.google.gerrit.server.securestore.SecureStore;

public class AutoReloadSecureCredentialsFactoryDecorator implements CredentialsFactory {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final AtomicReference<SecureCredentialsFactory> secureCredentialsFactory;
  private volatile long secureCredentialsFactoryLoadTs;
  private final SitePaths site;
  private ReplicationConfig config;
  private final SecureStore secureStore;
  private final Provider<DefaultSecureStore> defaultSecureStore;
  private final Provider<ReplicationConfig> replicationConfigProvider;
  private final Provider<ConfigParser> configParserProvider;

  @Inject
  public AutoReloadSecureCredentialsFactoryDecorator(
      SitePaths site,
      ReplicationConfig config,
      SecureStore secureStore,
      Provider<DefaultSecureStore> defaultSecureStore,
      Provider<ConfigParser> configParserProvider,
      Provider<ReplicationConfig> replicationConfigProvider)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.config = config;
    this.secureStore = secureStore;
    this.defaultSecureStore = defaultSecureStore;
    this.configParserProvider = configParserProvider;
    this.replicationConfigProvider = replicationConfigProvider;

    this.secureCredentialsFactory =
        new AtomicReference<>(
            new SecureCredentialsFactory(
                secureStore, defaultSecureStore, configParserProvider, replicationConfigProvider));
    this.secureCredentialsFactoryLoadTs = getSecureConfigLastEditTs();
  }

  private long getSecureConfigLastEditTs() {
    if (!Files.exists(site.secure_config)) {
      return 0L;
    }
    return lastModified(site.secure_config);
  }

  @Override
  public CredentialsProvider create(String remoteName) {
    try {
      if (needsReload()) {
        secureCredentialsFactory.compareAndSet(
            secureCredentialsFactory.get(),
            new SecureCredentialsFactory(
                secureStore, defaultSecureStore, configParserProvider, replicationConfigProvider));
        secureCredentialsFactoryLoadTs = getSecureConfigLastEditTs();
        logger.atInfo().log("secure.config reloaded as it was updated on the file system");
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error while trying to reload "
              + "secure.config: keeping existing credentials");
    }

    return secureCredentialsFactory.get().create(remoteName);
  }

  private boolean needsReload() {
    return config.getConfig().getBoolean("gerrit", "autoReload", false)
        && getSecureConfigLastEditTs() != secureCredentialsFactoryLoadTs;
  }
}
