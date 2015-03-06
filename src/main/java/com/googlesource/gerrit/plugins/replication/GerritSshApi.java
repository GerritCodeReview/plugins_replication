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
  private static final Logger log = LoggerFactory.getLogger(GerritSshApi.class);

  private final SshHelper sshHelper;

  private final Set<URIish> withoutDeleteProjectPlugin = new HashSet<>();

  @Inject
  protected GerritSshApi(SshHelper sshHelper) {
    this.sshHelper = sshHelper;
  }

  protected boolean createProject(URIish uri, String projectName, String head) {
    OutputStream errStream = sshHelper.newErrorBufferStream();
    String cmd = "gerrit create-project --branch " + head + " " + projectName;
    try {
      execute(uri, cmd, errStream);
    } catch (IOException e) {
      logError("creating", uri, errStream, cmd, e);
      return false;
    }
    return true;
  }

  protected boolean deleteProject(URIish uri, String projectName) {
    if (!withoutDeleteProjectPlugin.contains(uri)) {
      OutputStream errStream = sshHelper.newErrorBufferStream();
      String cmd = "deleteproject delete --yes-really-delete --force " + projectName;
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

  protected boolean updateHead(URIish uri, String projectName, String newHead) {
    OutputStream errStream = sshHelper.newErrorBufferStream();
    String cmd = "gerrit set-head " + projectName + " --new-head " + newHead;
    try {
      execute(uri, cmd, errStream);
    } catch (IOException e) {
      log.error(
          String.format(
              "Error updating HEAD of remote repository at %s to %s:\n"
                  + "  Exception: %s\n  Command: %s\n  Output: %s",
              uri, newHead, e, cmd, errStream),
          e);
      return false;
    }
    return true;
  }

  private URIish toSshUri(URIish uri) throws URISyntaxException {
    if (uri.getHost() != null) {
      return new URIish(uri.toString().replace("gerrit\\+ssh://", "ssh://"));
    }
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

  private int execute(URIish uri, String cmd, OutputStream errStream) throws IOException {
    try {
      URIish sshUri = toSshUri(uri);
      return sshHelper.executeRemoteSsh(sshUri, cmd, errStream);
    } catch (URISyntaxException e) {
      log.error(String.format("Cannot convert %s to SSH uri", uri), e);
    }
    return -1;
  }

  public void logError(String msg, URIish uri, OutputStream errStream, String cmd, IOException e) {
    log.error(
        String.format(
            "Error %s remote repository at %s:\n" + "  Exception: %s\n  Command: %s\n  Output: %s",
            msg, uri, e, cmd, errStream),
        e);
  }
}
