// Copyright (C) 2026 The Android Open Source Project
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

import com.google.common.base.Strings;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.io.StreamCopyThread;

@Singleton
public class ProjectRepairer {
  private final GitRepositoryManager gitManager;
  private final ReplicationConfig replicationConfig;

  @Inject
  ProjectRepairer(GitRepositoryManager gitManager, ReplicationConfig replicationConfig) {
    this.gitManager = gitManager;
    this.replicationConfig = replicationConfig;
  }

  public boolean repair(Project.NameKey project, URIish uri, OutputStream out, boolean copyPacks) {
    if (copyPacks && !copyPackTo(project, uri, out)) {
      repLog.atSevere().log("Repair failed for %s on %s", project.get(), uri);
      return false;
    }
    return true;
  }

  public static boolean canCopy(URIish uri) {
    return AdminApiFactory.isSSH(uri) && !AdminApiFactory.isGerrit(uri);
  }

  private boolean copyPackTo(Project.NameKey project, URIish uri, OutputStream out) {
    if (Strings.isNullOrEmpty(uri.getHost())) {
      repLog.atSevere().log("Cannot repair %s: URI has no host: %s", project.get(), uri);
      return false;
    }
    if (Strings.isNullOrEmpty(uri.getPath())) {
      repLog.atSevere().log("Cannot repair %s: URI has no path: %s", project.get(), uri);
      return false;
    }

    Path packDir;
    try (Repository repo = gitManager.openRepository(project)) {
      packDir = repo.getDirectory().toPath().resolve("objects").resolve("pack");
    } catch (IOException e) {
      repLog.atSevere().withCause(e).log("Cannot open repository %s for repair", project.get());
      return false;
    }

    if (!Files.isDirectory(packDir)) {
      repLog.atSevere().log("No objects/pack directory for project %s", project.get());
      return false;
    }

    return copyInOrder(packDir, uri, out);
  }

  private boolean copyInOrder(Path packDir, URIish uri, OutputStream out) {
    try {
      return copy(packDir, uri, out, "*.pack") == 0
          && copy(packDir, uri, out, "*.idx", "*.bitmap", "*.rev") == 0;
    } catch (InterruptedException e) {
      repLog.atWarning().withCause(e).log("Interrupted during copy to %s", uri);
      return false;
    }
  }

  private int copy(Path src, URIish uri, OutputStream out, String... includes)
      throws InterruptedException {
    List<String> cmd = new ArrayList<>();
    cmd.add(replicationConfig.getRsyncPath());
    cmd.add("-avP");
    cmd.add("-e");
    cmd.add(buildSshTransport(uri));
    for (String inc : includes) {
      cmd.add("--include=" + inc);
    }
    cmd.add("--exclude=*");
    cmd.add(src.toAbsolutePath().normalize() + "/");
    cmd.add(buildCopyDestination(uri));

    repLog.atInfo().log("Running repair cmd: %s", String.join(" ", cmd));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      repLog.atWarning().withCause(e).log("Copy to %s failed", uri);
      return -1;
    }

    StreamCopyThread outStream = new StreamCopyThread(p.getInputStream(), out);
    outStream.setName("copy-packs-output");
    outStream.start();
    try {
      int code = p.waitFor();
      outStream.join();
      if (code != 0) {
        repLog.atWarning().log("Copy to %s failed with exit code %d", uri, code);
      }
      return code;
    } catch (InterruptedException e) {
      p.destroyForcibly();
      outStream.halt();
      return -1;
    }
  }

  private static String buildCopyDestination(URIish uri) {
    String host = uri.getHost();
    String path = uri.getPath();
    String remotePackPath = QuotedString.BOURNE.quote(path + "/objects/pack/");
    String user = uri.getUser();
    if (user != null && !user.isEmpty()) {
      return user + "@" + host + ":" + remotePackPath;
    }
    return host + ":" + remotePackPath;
  }

  private static String buildSshTransport(URIish uri) {
    StringBuilder sb = new StringBuilder("ssh -o BatchMode=yes");
    int port = uri.getPort();
    if (port > 0) {
      sb.append(" -p ").append(port);
    }
    return sb.toString();
  }
}
