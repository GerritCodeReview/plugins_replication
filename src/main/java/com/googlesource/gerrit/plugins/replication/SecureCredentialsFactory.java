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

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.securestore.DefaultSecureStore;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.Inject;
import com.google.inject.Provider;

/** Looks up a remote's password in secure.config. */
public class SecureCredentialsFactory implements CredentialsFactory {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String USER = "username";
  private static final String PASSWORD = "password";

  private final SecureStore secureStore;

  private final Provider<DefaultSecureStore> defaultSecureStore;
  private final Provider<ReplicationConfig> replicationConfigProvider;
  private final Provider<ConfigParser> configParserProvider;

  @Inject
  public SecureCredentialsFactory(
      SecureStore secureStore,
      Provider<DefaultSecureStore> defaultSecureStore,
      Provider<ConfigParser> configParserProvider,
      Provider<ReplicationConfig> replicationConfigProvider)
      throws ConfigInvalidException, IOException {
    this.secureStore = secureStore;
    this.defaultSecureStore = defaultSecureStore;
    this.configParserProvider = configParserProvider;
    this.replicationConfigProvider = replicationConfigProvider;

    secureStore.reload();
  }

  @Override
  public CredentialsProvider create(String remoteName) {
    String user = Objects.toString(getConfigValue("remote", remoteName, USER), "");
    String pass = Objects.toString(getConfigValue("remote", remoteName, PASSWORD), "");

    return new UsernamePasswordCredentialsProvider(user, pass);
  }

  private String getConfigValue(String section, String subsection, String name) {
    try {
      String value = secureStore.get(section, subsection, name);

      if (value == null) {
        logger.atWarning().log(
            "SecureStore information for section: %s, "
                + "subsection: %s, name: %s was not provided.",
            section, subsection, name);
      } else {
        return value;
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error while trying to load "
              + "SecureStore information for section: %s, "
              + "subsection: %s, name: %s.d ",
          section, subsection, name);
      // For backwards compatibility, a fallback will be used to read in plain text in the
      // situation of the secure store being used. The exception will nevertheless be logged
      // for system administrators to correct the secure.config configuration file
      if (!(secureStore instanceof DefaultSecureStore)) {
        String value = defaultSecureStore.get().get(section, subsection, name);

        if (value != null) {
          return value;
        }
      }
    }

    return getConfigValueFromRemoteDestination(subsection, name);
  }

  private String getConfigValueFromRemoteDestination(String remoteName, String propertyName) {
    try {
      List<RemoteConfiguration> newValidDestinations =
          configParserProvider.get().parseRemotes(replicationConfigProvider.get().getConfig());

      // There should be only one configuration with a given remote name
      RemoteConfiguration conf =
          newValidDestinations.stream()
              .filter(a -> remoteName.equals(a.getRemoteConfig().getName()))
              .findAny()
              .get();

      if (conf == null) {
        return null;
      }

      switch (propertyName) {
        case USER:
          return conf.getUsername();

        case PASSWORD:
          return conf.getPassword();

        default:
          return null;
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Cannot read replication configuration");
    }

    return null;
  }
}
