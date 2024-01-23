// Copyright (C) 2011 The Android Open Source Project
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
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.Objects;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;
import com.google.gerrit.server.securestore.SecureStore;


/** Looks up a remote's password in secure.config. */
public class SecureCredentialsFactory implements CredentialsFactory {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SecureStore secureStore;

  @Inject
  public SecureCredentialsFactory(SecureStore secureStore) throws ConfigInvalidException, IOException {
    this.secureStore = secureStore;

    secureStore.reload();
  }

  @Override
  public CredentialsProvider create(String remoteName) {
    String user = Objects.toString(getConfigValue("remote", remoteName, "username"), "");
    String pass = Objects.toString(getConfigValue("remote", remoteName, "password"), "");

    return new UsernamePasswordCredentialsProvider(user, pass);
  }

  private String getConfigValue(String section, String subsection, String name) {
    try {
      String value = secureStore.get(section, subsection, name);

      if (value == null)  {
        logger.atWarning().log(
            "secure.config information for section: %s, "
                + "subsection: %s, name: %s was not provided. Assuming blank.", section, subsection, name);
      }

      return value;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error while trying to load "
              + "secure.config information for section: %s, "
              + "subsection: %s, name: %s. Assuming blank.", section, subsection, name);
      // To distinguish between the value not set and an encryption problem
      return "";
    }
  }
}
