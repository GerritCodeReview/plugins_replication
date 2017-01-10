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

import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

/** Looks up a remote's password in secure.config. */
class SecureCredentialsFactory implements CredentialsFactory {
  private final Config config;

  @Inject
  SecureCredentialsFactory(SitePaths site) throws ConfigInvalidException, IOException {
    config = load(site);
  }

  private static Config load(SitePaths site) throws ConfigInvalidException, IOException {
    FileBasedConfig cfg = new FileBasedConfig(site.secure_config.toFile(), FS.DETECTED);
    if (cfg.getFile().exists() && cfg.getFile().length() > 0) {
      try {
        cfg.load();
      } catch (ConfigInvalidException e) {
        throw new ConfigInvalidException(
            String.format("Config file %s is invalid: %s", cfg.getFile(), e.getMessage()), e);
      } catch (IOException e) {
        throw new IOException(
            String.format("Cannot read %s: %s", cfg.getFile(), e.getMessage()), e);
      }
    }
    return cfg;
  }

  @Override
  public SecureCredentialsProvider create(String remoteName) {
    String user = config.getString("remote", remoteName, "username");
    String pass = config.getString("remote", remoteName, "password");
    return new SecureCredentialsProvider(user, pass);
  }
}
