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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.UsedAt.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.CredentialsProvider;

@UsedAt(Project.PLUGIN_PULL_REPLICATION)
public class AutoReloadSecureCredentialsFactoryDecorator implements CredentialsFactory {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AtomicReference<CredentialsFactory> secureCredentialsFactory;
  private final SecureStore secureStore;
  private final SitePaths site;
  private final ReplicationConfig config;

  @Inject
  public AutoReloadSecureCredentialsFactoryDecorator(
      SitePaths site, SecureStore secureStore, ReplicationConfig config)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.secureStore = secureStore;
    this.config = config;
    this.secureCredentialsFactory =
        new AtomicReference<>(newSecureCredentialsFactory(site, secureStore, config));
    if (config.useLegacyCredentials()) {
      logger.atWarning().log(
          "Using legacy credentials in clear text in secure.config. Please encrypt your credentials"
              + " using 'java -jar gerrit.war passwd' for each remote, remove the"
              + " gerrit.useLegacyCredentials in replication.config and then reload the replication"
              + " plugin.");
    }
  }

  private static CredentialsFactory newSecureCredentialsFactory(
      SitePaths site, SecureStore secureStore, ReplicationConfig config)
      throws ConfigInvalidException, IOException {
    if (config.useLegacyCredentials()) {
      return new LegacyCredentialsFactory(site);
    }
    return new SecureCredentialsFactory(secureStore);
  }

  @Override
  public CredentialsProvider create(String remoteName) {
    try {
      if (needsReload()) {
        secureStore.reload();
        secureCredentialsFactory.compareAndSet(
            secureCredentialsFactory.get(), newSecureCredentialsFactory(site, secureStore, config));
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
    return config.getConfig().getBoolean("gerrit", "autoReload", false) && secureStore.isOutdated();
  }

  @Override
  public boolean validate(String remoteConfigName) {
    return secureCredentialsFactory.get().validate(remoteConfigName);
  }
}
