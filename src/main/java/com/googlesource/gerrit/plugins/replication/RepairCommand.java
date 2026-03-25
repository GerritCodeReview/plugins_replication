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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

  @Option(name = "--full", usage = "run all supported repair actions (default)")
  private boolean full;

  @Inject private GitRepositoryManager gitManager;
  @Inject private ProjectCache projectCache;
  @Inject private ReplicationDestinations destinations;
  @Inject private ReplicationStarter replicationStarter;

  private final Object outputLock = new Object();

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

    if (!copyPacks) {
      full = true;
    }

    Set<URIish> failedUris = new HashSet<>();
    if (full || copyPacks) {
      failedUris.addAll(runCopyPacks(project));
    }

    if (!failedUris.isEmpty()) {
      throw new UnloggedFailure(1, "Repair failed for " + failedUris.size() + " destination(s)");
    }

    writeStdOutSync("\nRunning replication start for " + project.get() + " ...");
    replicationStarter.start(
        urlMatch,
        Set.of(),
        new ReplicationFilter(List.of(project.get())),
        /* now= */ true,
        /* wait= */ true,
        this);
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

    List<CopyTarget> copyTargets = new ArrayList<>();
    Set<URIish> seen = new HashSet<>();
    for (Destination destination : destinations.getAll(ReplicationConfig.FilterType.ALL)) {
      if (!destination.wouldPushProject(project)) {
        continue;
      }
      String rsyncPath = destination.getRsyncPath();
      for (URIish uri : destination.getURIs(project, urlMatch)) {
        if (!canCopy(uri)) {
          writeStdErrSync(
              "Warning: skipping " + uri + " as copy-packs only supports plain SSH destinations");
          continue;
        }
        if (seen.add(uri)) {
          copyTargets.add(new CopyTarget(uri, rsyncPath));
        }
      }
    }

    if (copyTargets.isEmpty()) {
      throw die("No matching destinations found");
    }

    Set<URIish> failedUris = new HashSet<>();
    for (CopyTarget target : copyTargets) {
      writeStdOutSync("Copying pack files to " + target.uri() + " ...");
      if (!copyInOrder(packDir, target)) {
        failedUris.add(target.uri());
      }
    }
    return failedUris;
  }

  private record CopyTarget(URIish uri, String rsyncPath) {}

  private boolean copyInOrder(Path packDir, CopyTarget target) throws Failure {
    return runCopy(packDir, target, "*.pack")
        && runCopy(packDir, target, "*.idx", "*.bitmap", "*.rev");
  }

  private boolean runCopy(Path src, CopyTarget target, String... includes) throws Failure {
    int retCode;
    try {
      retCode = copy(src, target, includes);
    } catch (IOException e) {
      throw die(e);
    } catch (InterruptedException e) {
      throw die("Interrupted during copy to " + target.uri(), e);
    }
    if (retCode != 0) {
      writeStdErrSync("Warning: copy to " + target.uri() + " failed with exit code " + retCode);
      return false;
    }
    return true;
  }

  private static boolean canCopy(URIish uri) {
    return AdminApiFactory.isSSH(uri) && !AdminApiFactory.isGerrit(uri);
  }

  private int copy(Path src, CopyTarget target, String... includes)
      throws IOException, InterruptedException, UnloggedFailure {
    URIish uri = target.uri();
    List<String> cmd = new ArrayList<>();
    cmd.add(target.rsyncPath());
    cmd.add("-avP");
    cmd.add("-e");
    cmd.add(buildSshTransport(uri));
    for (String inc : includes) {
      cmd.add("--include=" + inc);
    }
    cmd.add("--exclude=*");
    cmd.add(src.toAbsolutePath().normalize() + "/");
    cmd.add(buildCopyDestination(uri));

    logger.atInfo().log("Running repair cmd: %s", String.join(" ", cmd));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();

    stdout.flush();
    StreamCopyThread outStream =
        new StreamCopyThread(p.getInputStream(), getFlushingOutputStream());
    outStream.setName("copy-packs-output");
    outStream.start();
    try {
      int code = p.waitFor();
      outStream.join();
      return code;
    } catch (InterruptedException e) {
      p.destroyForcibly();
      outStream.halt();
      throw e;
    }
  }

  private OutputStream getFlushingOutputStream() {
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
