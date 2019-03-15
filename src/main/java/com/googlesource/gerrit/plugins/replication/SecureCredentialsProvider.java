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

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/** Looks up a remote's password in secure.config. */
class SecureCredentialsProvider extends CredentialsProvider {
  private final String cfgUser;
  private final String cfgPass;

  SecureCredentialsProvider(String user, String pass) {
    cfgUser = user;
    cfgPass = pass;
  }

  @Override
  public boolean isInteractive() {
    return false;
  }

  @Override
  public boolean supports(CredentialItem... items) {
    for (CredentialItem i : items) {
      if (i instanceof CredentialItem.Username) {
        continue;
      } else if (i instanceof CredentialItem.Password) {
        continue;
      } else {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
    String username = uri.getUser();
    if (username == null) {
      username = cfgUser;
    }
    if (username == null) {
      return false;
    }

    String password = uri.getPass();
    if (password == null) {
      password = cfgPass;
    }
    if (password == null) {
      return false;
    }

    for (CredentialItem i : items) {
      if (i instanceof CredentialItem.Username) {
        ((CredentialItem.Username) i).setValue(username);
      } else if (i instanceof CredentialItem.Password) {
        ((CredentialItem.Password) i).setValue(password.toCharArray());
      } else {
        throw new UnsupportedCredentialItem(uri, i.getPromptText());
      }
    }
    return true;
  }
}
