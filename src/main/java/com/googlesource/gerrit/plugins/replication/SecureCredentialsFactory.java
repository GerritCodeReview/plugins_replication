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

import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.util.Objects;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** Looks up a remote's password in secure.config. */
class SecureCredentialsFactory implements CredentialsFactory {
  private final SecureStore secureStore;
  private final ReplicationConfig replicationConfig;

  @Inject
  public SecureCredentialsFactory(SecureStore secureStore, ReplicationConfig replicationConfig)
      throws ConfigInvalidException, IOException {
    this.secureStore = secureStore;
    this.replicationConfig = replicationConfig;
  }

  @Override
  public CredentialsProvider create(String remoteName) {
    String user =
        Objects.toString(
            replicationConfig.getConfig().getString("remote", remoteName, "username"), "");
    String pass = Objects.toString(secureStore.get("remote", remoteName, "password"), "");
    return new UsernamePasswordCredentialsProvider(user, pass);
  }
}
