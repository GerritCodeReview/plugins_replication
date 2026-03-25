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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.io.StreamCopyThread;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@CommandMetaData(name = "copy-packs", description = "Copy pack files to destinations for a project")
final class CopyPacksCommand extends SshCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Argument(index = 0, required = true, metaVar = "PROJECT", usage = "project name")
  private String projectName;

  @Option(
      name = "--url",
      metaVar = "SUBSTRING",
      usage = "substring URL must match (or * to match everything)")
  private String urlMatch;

  @Inject private GitRepositoryManager gitManager;
  @Inject private ProjectCache projectCache;
  @Inject private ReplicationDestinations destinations;

  @Override
  protected void run() throws Failure {
    Project.NameKey project = Project.nameKey(projectName);
    try {
      if (projectCache.get(project).isEmpty()) {
        throw die("Project with name " + projectName + " not found.");
      }
    } catch (StorageException e) {
      throw die(e);
    }

    Path packDir;
    try (Repository repo = gitManager.openRepository(project)) {
      packDir = repo.getDirectory().toPath().resolve("objects").resolve("pack");
    } catch (IOException e) {
      throw die(e);
    }

    if (!Files.isDirectory(packDir)) {
      throw die("No objects/pack directory for project " + projectName);
    }

    Set<URIish> uris = new HashSet<>();
    for (Destination destination : destinations.getAll(ReplicationConfig.FilterType.ALL)) {
      if (!destination.wouldPushProject(project)) {
        continue;
      }
      for (URIish uri : destination.getURIs(project, urlMatch)) {
        if (canCopy(uri)) {
          uris.add(uri);
        }
      }
    }

    if (uris.isEmpty()) {
      writeStdOutSync("No matching destinations found.");
      return;
    }

    List<Path> packFiles;
    try {
      packFiles = listPackFilesToCopy(packDir);
    } catch (IOException e) {
      throw die(e);
    }
    if (packFiles.isEmpty()) {
      writeStdOutSync("No pack files found for project " + projectName);
      return;
    }

    for (URIish uri : uris) {
      writeStdOutSync("Copying pack files to " + uri + " ...");
      int code;
      try {
        code = runCopy(packFiles, uri);
      } catch (IOException e) {
        throw die(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw die("interrupted during copy to " + uri, e);
      }
      if (code != 0) {
        throw die("copy failed with exit code " + code + " for " + uri);
      }
    }
  }

  private static boolean canCopy(URIish uri) {
    return AdminApiFactory.isSSH(uri) && !AdminApiFactory.isGerrit(uri);
  }

  private static List<Path> listPackFilesToCopy(Path packDir) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(packDir)) {
      for (Path p : stream) {
        if (!Files.isRegularFile(p)) {
          continue;
        }
        String name = p.getFileName().toString();
        if (name.endsWith(".pack")
            || name.endsWith(".idx")
            || name.endsWith(".bitmap")
            || name.endsWith(".rev")) {
          files.add(p.toAbsolutePath().normalize());
        }
      }
    }
    files.sort(Comparator.naturalOrder());
    return files;
  }

  private int runCopy(List<Path> packFiles, URIish uri)
      throws IOException, InterruptedException, UnloggedFailure {
    List<String> cmd = new ArrayList<>();
    cmd.add("rsync");
    cmd.add("-avP");
    cmd.add("--partial");
    cmd.add("-e");
    cmd.add("ssh -o BatchMode=yes");
    for (Path f : packFiles) {
      cmd.add(f.toString());
    }
    cmd.add(buildCopyDestination(uri));

    logger.atInfo().log("Running copy-packs: %s", String.join(" ", cmd));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();

    stdout.flush();
    StreamCopyThread outStream = new StreamCopyThread(p.getInputStream(), getOutputStream());
    outStream.setName("copy-packs-output");
    outStream.start();
    try {
      int code = p.waitFor();
      outStream.join();
      return code;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
      try {
        outStream.halt();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      throw e;
    }
  }

  private OutputStream getOutputStream() {
    return new OutputStream() {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        out.flush();
      }

      @Override
      public void write(int b) throws IOException {
        out.write(b);
        out.flush();
      }
    };
  }

  private String buildCopyDestination(URIish uri) throws UnloggedFailure {
    String host = uri.getHost();
    if (host == null || host.isEmpty()) {
      throw die("URI has no host: " + uri);
    }

    String path = uri.getPath();
    if (path == null || path.isEmpty()) {
      throw die("URI has no path: " + uri);
    }
    String remotePackPath = path + "/objects/pack/";

    String user = uri.getUser();
    if (user != null && !user.isEmpty()) {
      return user + "@" + host + ":" + remotePackPath;
    }
    return host + ":" + remotePackPath;
  }

  public void writeStdOutSync(String message) {
    stdout.println(message);
    stdout.flush();
  }
}
