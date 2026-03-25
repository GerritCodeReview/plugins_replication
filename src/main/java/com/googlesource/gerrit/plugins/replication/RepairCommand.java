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
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
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
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.io.StreamCopyThread;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

@RequiresCapability(StartReplicationCapability.START_REPLICATION)
@CommandMetaData(name = "repair", description = "Repair a project on replication destinations")
final class RepairCommand extends SshCommand implements PushResultProcessing.SshOutputCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Argument(index = 0, required = true, metaVar = "PROJECT", usage = "project name")
  private String projectName;

  @Option(
      name = "--url",
      metaVar = "SUBSTRING",
      usage = "substring URL must match (or * to match everything)")
  private String urlMatch;

  @Option(
      name = "--copy-packs",
      usage = "rsync objects/pack files to SSH destinations before triggering replication")
  private boolean copyPacks;

  @Inject private GitRepositoryManager gitManager;
  @Inject private ProjectCache projectCache;
  @Inject private ReplicationDestinations destinations;
  @Inject private ReplicationStarter replicationStarter;

  private final Object outputLock = new Object();

  @Override
  protected void run() throws Failure {
    if (!copyPacks) {
      throw die("--copy-packs is required");
    }

    Project.NameKey project = Project.nameKey(projectName);
    try {
      if (projectCache.get(project).isEmpty()) {
        throw die("Project with name " + projectName + " not found.");
      }
    } catch (StorageException e) {
      throw die(e);
    }

    Set<URIish> failedUris = runCopyPacks(project);

    writeStdOutSync("\nRunning replication start for " + project.get() + " ...");
    replicationStarter.start(
        urlMatch,
        Set.of(),
        new ReplicationFilter(List.of(project.get())),
        /* now= */ true,
        /* wait= */ true,
        this);

    if (!failedUris.isEmpty()) {
      throw new UnloggedFailure(
          1, "copy failed for " + failedUris.size() + " destination(s); see warnings above");
    }
  }

  private Set<URIish> runCopyPacks(Project.NameKey project) throws Failure {
    Path packDir;
    try (Repository repo = gitManager.openRepository(project)) {
      packDir = repo.getDirectory().toPath().resolve("objects").resolve("pack");
    } catch (IOException e) {
      throw die(e);
    }

    if (!Files.isDirectory(packDir)) {
      throw die("No objects/pack directory for project " + projectName);
    }

    Set<URIish> eligibleUris = new HashSet<>();
    for (Destination destination : destinations.getAll(ReplicationConfig.FilterType.ALL)) {
      if (!destination.wouldPushProject(project)) {
        writeStdErrSync(
            "warning: skipping remote "
                + destination.getRemoteConfigName()
                + " as project "
                + projectName
                + " is not configured to be pushed to it");
        continue;
      }
      for (URIish uri : destination.getURIs(project, urlMatch)) {
        if (canCopy(uri)) {
          eligibleUris.add(uri);
        } else {
          writeStdErrSync(
              "warning: skipping " + uri + " as copy-packs only supports plain SSH destinations");
        }
      }
    }

    if (eligibleUris.isEmpty()) {
      writeStdOutSync("No matching destinations found for copy-packs");
      return Set.of();
    }

    PackFiles packFiles;
    try {
      packFiles = listPackFilesToCopy(packDir);
    } catch (IOException e) {
      throw die(e);
    }
    if (packFiles.isEmpty()) {
      writeStdOutSync("No pack files found for project " + projectName);
      return Set.of();
    }

    Set<URIish> failedUris = new HashSet<>();
    for (URIish uri : eligibleUris) {
      writeStdOutSync("Copying pack files to " + uri + " ...");
      // Copy *.pack files first, then everything else
      if (!copyInOrder(packFiles, uri)) {
        failedUris.add(uri);
      }
    }
    return failedUris;
  }

  private boolean copyInOrder(PackFiles packFiles, URIish uri) throws Failure {
    return runCopy(packFiles.packs(), uri) && runCopy(packFiles.indexes(), uri);
  }

  private boolean runCopy(List<Path> files, URIish uri) throws Failure {
    if (files.isEmpty()) {
      return true;
    }
    int retCode;
    try {
      retCode = copy(files, uri);
    } catch (IOException e) {
      throw die(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw die("interrupted during copy to " + uri, e);
    }
    if (retCode != 0) {
      writeStdErrSync("warning: copy to " + uri + " failed with exit code " + retCode);
      return false;
    }
    return true;
  }

  private static boolean canCopy(URIish uri) {
    return AdminApiFactory.isSSH(uri) && !AdminApiFactory.isGerrit(uri);
  }

  private record PackFiles(List<Path> packs, List<Path> indexes) {
    boolean isEmpty() {
      return packs.isEmpty() && indexes.isEmpty();
    }
  }

  private static PackFiles listPackFilesToCopy(Path packDir) throws IOException {
    List<Path> packs = new ArrayList<>();
    List<Path> indexes = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(packDir)) {
      for (Path p : stream) {
        if (!Files.isRegularFile(p)) {
          continue;
        }
        String name = p.getFileName().toString();
        Path normalized = p.toAbsolutePath().normalize();
        if (name.endsWith(".pack")) {
          packs.add(normalized);
        } else if (name.endsWith(".idx") || name.endsWith(".bitmap") || name.endsWith(".rev")) {
          indexes.add(normalized);
        }
      }
    }
    packs.sort(Comparator.naturalOrder());
    indexes.sort(Comparator.naturalOrder());
    return new PackFiles(packs, indexes);
  }

  private int copy(List<Path> files, URIish uri)
      throws IOException, InterruptedException, UnloggedFailure {
    List<String> cmd = new ArrayList<>();
    cmd.add("rsync");
    cmd.add("-avP");
    cmd.add("-e");
    cmd.add(buildSshTransport(uri));
    for (Path f : files) {
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

  @Override
  public void writeStdOutSync(String message) {
    synchronized (outputLock) {
      stdout.println(message);
      stdout.flush();
    }
  }

  @Override
  public void writeStdErrSync(String message) {
    synchronized (outputLock) {
      stderr.println(message);
      stderr.flush();
    }
  }
}
