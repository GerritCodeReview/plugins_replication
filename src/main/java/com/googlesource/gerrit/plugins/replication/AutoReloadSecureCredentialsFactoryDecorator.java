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

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.CredentialsProvider;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;

public class AutoReloadSecureCredentialsFactoryDecorator implements CredentialsFactory {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final AtomicReference<SecureCredentialsFactory> secureCredentialsAtomicReference;
  private volatile long secureCredentialsFactoryLoadTs;
  private final SitePaths site;
  private ReplicationConfig config;
  private final Provider<SecureCredentialsFactory> secureCredentialsFactoryProvider;

  @Inject
  public AutoReloadSecureCredentialsFactoryDecorator(
      SitePaths site,
      ReplicationConfig config,
      Provider<SecureCredentialsFactory> secureCredentialsFactoryProvider)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.config = config;
    this.secureCredentialsFactoryProvider = secureCredentialsFactoryProvider;

    this.secureCredentialsAtomicReference = new AtomicReference<>(secureCredentialsFactoryProvider.get());
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
        secureCredentialsAtomicReference.compareAndSet(
            secureCredentialsAtomicReference.get(),
            secureCredentialsFactoryProvider.get());
        secureCredentialsFactoryLoadTs = getSecureConfigLastEditTs();
        logger.atInfo().log("secure.config reloaded as it was updated on the file system");
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error while trying to reload "
              + "secure.config: keeping existing credentials");
    }

    return secureCredentialsAtomicReference.get().create(remoteName);
  }

  private boolean needsReload() {
    return config.getConfig().getBoolean("gerrit", "autoReload", false)
        && getSecureConfigLastEditTs() != secureCredentialsFactoryLoadTs;
  }
}
