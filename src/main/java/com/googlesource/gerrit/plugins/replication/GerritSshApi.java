// Copyright (C) 2017 The Android Open Source Project
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

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ssh.SshAddressesModule;
import com.google.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GerritSshApi {
  static int SSH_COMMAND_FAILED = -1;
  private static final Logger log = LoggerFactory.getLogger(GerritSshApi.class);
  private static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  private final SshHelper sshHelper;

  private final Set<URIish> withoutDeleteProjectPlugin = new HashSet<>();

  @Inject
  protected GerritSshApi(SshHelper sshHelper) {
    this.sshHelper = sshHelper;
  }

  protected boolean createProject(URIish uri, Project.NameKey projectName, String head) {
    OutputStream errStream = sshHelper.newErrorBufferStream();
    String cmd = "gerrit create-project --branch " + head + " " + projectName.get();
    try {
      execute(uri, cmd, errStream);
    } catch (IOException e) {
      logError("creating", uri, errStream, cmd, e);
      return false;
    }
    return true;
  }

  protected boolean deleteProject(URIish uri, Project.NameKey projectName) {
    if (!withoutDeleteProjectPlugin.contains(uri)) {
      OutputStream errStream = sshHelper.newErrorBufferStream();
      String cmd = "deleteproject delete --yes-really-delete --force " + projectName.get();
      int exitCode = -1;
      try {
        exitCode = execute(uri, cmd, errStream);
      } catch (IOException e) {
        logError("deleting", uri, errStream, cmd, e);
        return false;
      }
      if (exitCode == 1) {
        log.info(
            "DeleteProject plugin is not installed on {}; will not try to forward this operation to that host");
        withoutDeleteProjectPlugin.add(uri);
        return true;
      }
    }
    return true;
  }

  protected boolean updateHead(URIish uri, Project.NameKey projectName, String newHead) {
    OutputStream errStream = sshHelper.newErrorBufferStream();
    String cmd = "gerrit set-head " + projectName.get() + " --new-head " + newHead;
    try {
      execute(uri, cmd, errStream);
    } catch (IOException e) {
      log.error(
          "Error updating HEAD of remote repository at {} to {}:\n"
              + "  Exception: {}\n  Command: {}\n  Output: {}",
          uri,
          newHead,
          e,
          cmd,
          errStream,
          e);
      return false;
    }
    return true;
  }

  private URIish toSshUri(URIish uri) throws URISyntaxException {
    String uriStr = uri.toString();
    if (uri.getHost() != null && uriStr.startsWith(GERRIT_ADMIN_PROTOCOL_PREFIX)) {
      return new URIish(uriStr.substring(0, GERRIT_ADMIN_PROTOCOL_PREFIX.length()));
    }
    String rawPath = uri.getRawPath();
    if (!rawPath.endsWith("/")) {
      rawPath = rawPath + "/";
    }
    URIish sshUri = new URIish("ssh://" + rawPath);
    if (sshUri.getPort() < 0) {
      sshUri = sshUri.setPort(SshAddressesModule.DEFAULT_PORT);
    }
    return sshUri;
  }

  private int execute(URIish uri, String cmd, OutputStream errStream) throws IOException {
    try {
      URIish sshUri = toSshUri(uri);
      return sshHelper.executeRemoteSsh(sshUri, cmd, errStream);
    } catch (URISyntaxException e) {
      log.error("Cannot convert {} to SSH uri", uri, e);
    }
    return SSH_COMMAND_FAILED;
  }

  public void logError(String msg, URIish uri, OutputStream errStream, String cmd, IOException e) {
    log.error(
        "Error {} remote repository at {}:\n  Exception: {}\n  Command: {}\n  Output: {}",
        msg,
        uri,
        e,
        cmd,
        errStream,
        e);
  }
}
