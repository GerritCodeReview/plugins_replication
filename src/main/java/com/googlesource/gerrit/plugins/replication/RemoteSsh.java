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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.reviewdb.client.Project;
import java.io.IOException;
import java.io.OutputStream;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.QuotedString;

public class RemoteSsh implements AdminApi {

  private final SshHelper sshHelper;
  private URIish uri;

  RemoteSsh(SshHelper sshHelper, URIish uri) {
    this.sshHelper = sshHelper;
    this.uri = uri;
  }

  @Override
  public void createProject(Project.NameKey project, String head) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "mkdir -p " + quotedPath + " && cd " + quotedPath + " && git init --bare";
    if (head != null) {
      cmd = cmd + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(head);
    }
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
      repLog.info("Created remote repository: {}", uri);
    } catch (IOException e) {
      repLog.error(
          "Error creating remote repository at {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          e,
          cmd,
          errStream,
          e);
    }
  }

  @Override
  public void deleteProject(Project.NameKey project) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "rm -rf " + quotedPath;
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
      repLog.info("Deleted remote repository: {}", uri);
    } catch (IOException e) {
      repLog.error(
          "Error deleting remote repository at {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          e,
          cmd,
          errStream,
          e);
    }
  }

  @Override
  public void updateHead(Project.NameKey project, String newHead) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd =
        "cd " + quotedPath + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(newHead);
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
    } catch (IOException e) {
      repLog.error(
          "Error updating HEAD of remote repository at {} to {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          newHead,
          e,
          cmd,
          errStream,
          e);
    }
  }
}
