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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ssh.SshAddressesModule;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;

public class GerritSshApi implements AdminApi {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static int SSH_COMMAND_FAILED = -1;
  static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  private final SshHelper sshHelper;
  private final URIish uri;

  private final Set<URIish> withoutDeleteProjectPlugin = new HashSet<>();

  protected GerritSshApi(SshHelper sshHelper, URIish uri) {
    this.sshHelper = sshHelper;
    this.uri = uri;
  }

  @Override
  public boolean createProject(Project.NameKey projectName, String head) {
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

  @Override
  public void deleteProject(Project.NameKey projectName) {
    if (!withoutDeleteProjectPlugin.contains(uri)) {
      OutputStream errStream = sshHelper.newErrorBufferStream();
      String cmd = "deleteproject delete --yes-really-delete --force " + projectName.get();
      int exitCode = -1;
      try {
        exitCode = execute(uri, cmd, errStream);
      } catch (IOException e) {
        logError("deleting", uri, errStream, cmd, e);
      }
      if (exitCode == 1) {
        logger.atInfo().log(
            "DeleteProject plugin is not installed on %s;"
                + " will not try to forward this operation to that host");
        withoutDeleteProjectPlugin.add(uri);
      }
    }
  }

  @Override
  public void updateHead(Project.NameKey projectName, String newHead) {
    OutputStream errStream = sshHelper.newErrorBufferStream();
    String cmd = "gerrit set-head " + projectName.get() + " --new-head " + newHead;
    try {
      execute(uri, cmd, errStream);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Error updating HEAD of remote repository at %s to %s:\n"
              + "  Exception: %s\n  Command: %s\n  Output: %s",
          uri, newHead, e, cmd, errStream);
    }
  }

  private URIish toSshUri(URIish uri) throws URISyntaxException {
    String uriStr = uri.toString();
    if (uri.getHost() != null && uriStr.startsWith(GERRIT_ADMIN_PROTOCOL_PREFIX)) {
      return new URIish(uriStr.substring(GERRIT_ADMIN_PROTOCOL_PREFIX.length()));
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
      logger.atSevere().withCause(e).log("Cannot convert %s to SSH uri", uri);
    }
    return SSH_COMMAND_FAILED;
  }

  public void logError(String msg, URIish uri, OutputStream errStream, String cmd, IOException e) {
    logger.atSevere().withCause(e).log(
        "Error %s remote repository at %s:\n  Exception: %s\n  Command: %s\n  Output: %s",
        msg, uri, e, cmd, errStream);
  }
}
