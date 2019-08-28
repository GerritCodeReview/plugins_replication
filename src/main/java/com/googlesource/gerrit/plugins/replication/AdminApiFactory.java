// Copyright (C) 2018 The Android Open Source Project
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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

/** Factory for creating an {@link AdminApi} instance for a remote URI. */
public interface AdminApiFactory {
  /**
   * Create an {@link AdminApi} for the given remote URI.
   *
   * @param uri the remote URI.
   * @return An API for the given remote URI, or {@code Optional.empty} if there is no appropriate
   *     API for the URI.
   */
  Optional<AdminApi> create(URIish uri);

  @Singleton
  static class DefaultAdminApiFactory implements AdminApiFactory {
    protected final SshHelper sshHelper;
    protected final GerritRestApi.Factory gerritRestApiFactory;

    @Inject
    public DefaultAdminApiFactory(SshHelper sshHelper, GerritRestApi.Factory gerritRestApiFactory) {
      this.sshHelper = sshHelper;
      this.gerritRestApiFactory = gerritRestApiFactory;
    }

    @Override
    public Optional<AdminApi> create(URIish uri) {
      if (isGerrit(uri)) {
        return Optional.of(new GerritSshApi(sshHelper, uri));
      } else if (!uri.isRemote()) {
        return Optional.of(new LocalFS(uri));
      } else if (isSSH(uri)) {
        return Optional.of(new RemoteSsh(sshHelper, uri));
      }
      return Optional.empty();
    }
  }

  static boolean isGerrit(URIish uri) {
    String scheme = uri.getScheme();
    return scheme != null && scheme.toLowerCase().equals("gerrit+ssh");
  }

  static boolean isSSH(URIish uri) {
    if (!uri.isRemote()) {
      return false;
    }
    String scheme = uri.getScheme();
    if (scheme != null && scheme.toLowerCase().contains("ssh")) {
      return true;
    }
    if (scheme == null && uri.getHost() != null && uri.getPath() != null) {
      return true;
    }
    return false;
  }

  public static boolean isGerritHttp(URIish uri) {
    String scheme = uri.getScheme();
    return scheme != null && scheme.toLowerCase().contains("gerrit+http");
  }
}
