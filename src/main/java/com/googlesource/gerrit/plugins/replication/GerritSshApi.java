// Copyright (C) 2015 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.SshHelper.executeRemoteSsh;
import static com.googlesource.gerrit.plugins.replication.SshHelper.newErrorBufferStream;

import com.google.inject.Inject;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;

public class GerritSshApi {
  private static final Logger log = LoggerFactory.getLogger(GerritSshApi.class);

  private final ReplicationSshSessionFactoryProvider sessionFactoryProvider;

  @Inject
  protected GerritSshApi(ReplicationSshSessionFactoryProvider sessionFactoryProvider) {
    this.sessionFactoryProvider = sessionFactoryProvider;
  }

  protected boolean createProject(CredentialsProvider credentialsProvider,
      URIish uri, String projectName, String head) {
    OutputStream errStream = newErrorBufferStream();
    String cmd = "gerrit create-project --branch " + head + " " + projectName;
    try {
      execute(credentialsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      log.error(String.format("Error creating remote repository at %s:%n"
          + "  Exception: %s%n  Command: %s%n  Output: %s", uri, e,
          cmd, errStream), e);
      return false;
    }
    return true;
  }

  protected boolean deleteProject(SecureCredentialsProvider credsProvider, URIish uri,
      String projectName) {
    OutputStream errStream = newErrorBufferStream();
    String cmd =
        "deleteproject delete --yes-really-delete --force " + projectName;
    try {
      execute(credsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      log.error(String.format("Error deleting remote repository at %s:%n"
          + "  Exception: %s%n  Command: %s%n  Output: %s", uri, e,
          cmd, errStream), e);

      return false;
    }
    return true;
  }

  protected boolean updateHead(CredentialsProvider credsProvider, URIish uri,
      String projectName, String newHead) {
    OutputStream errStream = newErrorBufferStream();
    String cmd = "gerrit set-head " + projectName + " --new-head " + newHead;
    try {
      execute(credsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      log.error(String.format(
          "Error updating HEAD of remote repository at %s to %s:%n"
              + "  Exception: %s%n  Command: %s%n  Output: %s", uri,
          newHead, e, cmd, errStream), e);
      return false;
    }
    return true;
  }

  private URIish toSshUri(URIish uri) throws URISyntaxException {
    if (uri.getHost() != null) {
      return new URIish(uri.toString().replace("gerrit://", "ssh://"));
    } else {
      String rawPath = uri.getRawPath();
      if (!rawPath.endsWith("/")) {
        rawPath = rawPath + "/";
      }
      URIish sshUri = new URIish("ssh://" + rawPath);
      if (sshUri.getPort() < 0) {
        sshUri = sshUri.setPort(29418);
      }
      return sshUri;
    }
  }

  private void execute(CredentialsProvider credentialsProvider, URIish uri,
      String cmd, OutputStream errStream) throws IOException {
    try {
      URIish sshUri = toSshUri(uri);
      executeRemoteSsh(credentialsProvider, sessionFactoryProvider, sshUri, cmd,
          errStream);
    } catch (URISyntaxException e) {
      log.error(String.format("Cannot convert %s to SSH uri", uri), e);
    }
  }
}
