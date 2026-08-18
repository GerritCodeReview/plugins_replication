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
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.file.PackFile;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.io.StreamCopyThread;

@Singleton
public class ProjectRepairer {
  public enum Action {
    COPY_LOOSE_OBJECTS,
    COPY_PACKS;

    public static List<Action> all() {
      return List.of(values());
    }
  }

  private static final String OBJECTS_DIR = "objects/";
  private static final String PACK_DIR = OBJECTS_DIR + "pack/";
  private static final String SNAPSHOT_PREFIX = "replication-repair-snapshot-";

  private final GitRepositoryManager gitManager;
  private final ReplicationConfig replicationConfig;

  @Inject
  ProjectRepairer(GitRepositoryManager gitManager, ReplicationConfig replicationConfig) {
    this.gitManager = gitManager;
    this.replicationConfig = replicationConfig;
  }

  public boolean repair(
      Project.NameKey project, URIish uri, OutputStream out, Collection<Action> actions)
      throws InterruptedIOException {
    if (actions.isEmpty()) {
      return true;
    }

    Path objectsDir = objectsDir(project, uri);
    if (objectsDir == null) {
      return false;
    }

    boolean isRepaired = true;
    for (Action action : actions) {
      isRepaired &= repair(project, uri, out, objectsDir, action);
    }
    return isRepaired;
  }

  private boolean repair(
      Project.NameKey project, URIish uri, OutputStream out, Path objectsDir, Action action)
      throws InterruptedIOException {
    boolean isRepaired =
        switch (action) {
          case COPY_LOOSE_OBJECTS -> copyLooseObjectsTo(objectsDir, uri, out);
          case COPY_PACKS -> copyPacksTo(objectsDir, uri, out);
        };
    if (!isRepaired) {
      repLog.atSevere().log("Repair (%s) failed for %s on %s", action, project.get(), uri);
    }
    return isRepaired;
  }

  public static boolean canCopy(URIish uri) {
    return AdminApiFactory.isSSH(uri) && !AdminApiFactory.isGerrit(uri);
  }

  @Nullable
  private Path objectsDir(Project.NameKey project, URIish uri) {
    if (Strings.isNullOrEmpty(uri.getHost())) {
      repLog.atSevere().log("Cannot repair %s: URI has no host: %s", project.get(), uri);
      return null;
    }
    if (Strings.isNullOrEmpty(uri.getPath())) {
      repLog.atSevere().log("Cannot repair %s: URI has no path: %s", project.get(), uri);
      return null;
    }

    try (Repository repo = gitManager.openRepository(project)) {
      return repo.getDirectory().toPath().resolve("objects");
    } catch (IOException e) {
      repLog.atSevere().withCause(e).log("Cannot open repository %s for repair", project.get());
      return null;
    }
  }

  private boolean copyLooseObjectsTo(Path objectsDir, URIish uri, OutputStream out)
      throws InterruptedIOException {
    if (!Files.isDirectory(objectsDir)) {
      repLog.atSevere().log("No objects directory %s", objectsDir);
      return false;
    }

    try (Snapshot snapshot = Snapshot.create(objectsDir)) {
      return snapshot != null
          && snapshot.linkLooseObjects(objectsDir)
          && copy(snapshot.dir(), uri, out, OBJECTS_DIR, "/??/", "/??/*") == 0;
    }
  }

  private boolean copyPacksTo(Path objectsDir, URIish uri, OutputStream out)
      throws InterruptedIOException {
    Path packDir = objectsDir.resolve("pack");
    if (!Files.isDirectory(packDir)) {
      repLog.atSevere().log("No objects/pack directory %s", packDir);
      return false;
    }

    try (Snapshot snapshot = Snapshot.create(objectsDir)) {
      return snapshot != null
          && snapshot.linkPacks(packDir)
          && copy(snapshot.dir(), uri, out, PACK_DIR, glob(PackExt.PACK)) == 0
          && copy(
                  snapshot.dir(),
                  uri,
                  out,
                  PACK_DIR,
                  glob(PackExt.INDEX),
                  glob(PackExt.BITMAP_INDEX),
                  glob(PackExt.REVERSE_INDEX))
              == 0;
    }
  }

  private static String glob(PackExt ext) {
    return "*." + ext.getExtension();
  }

  private int copy(Path src, URIish uri, OutputStream out, String destDir, String... includes)
      throws InterruptedIOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(replicationConfig.getRsyncPath());
    cmd.add("-av");
    cmd.add("--progress");
    cmd.add("-e");
    cmd.add(buildSshTransport(uri));
    for (String inc : includes) {
      cmd.add("--include=" + inc);
    }
    cmd.add("--exclude=*");
    cmd.add(src.toAbsolutePath().normalize() + "/");
    cmd.add(buildCopyDestination(uri, destDir));

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
    outStream.setName("repair-copy-output");
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
      try {
        outStream.halt();
      } catch (InterruptedException ignored) {
        // ignore
      }
      throw (InterruptedIOException)
          new InterruptedIOException("Interrupted during copy to " + uri).initCause(e);
    }
  }

  private static String buildCopyDestination(URIish uri, String destDir) {
    String host = uri.getHost();
    String path = uri.getPath();
    String remotePath = QuotedString.BOURNE.quote(path + "/" + destDir);
    String user = uri.getUser();
    if (user != null && !user.isEmpty()) {
      return user + "@" + host + ":" + remotePath;
    }
    return host + ":" + remotePath;
  }

  private static String buildSshTransport(URIish uri) {
    StringBuilder sb = new StringBuilder("ssh -o BatchMode=yes");
    int port = uri.getPort();
    if (port > 0) {
      sb.append(" -p ").append(port);
    }
    return sb.toString();
  }

  private record Snapshot(Path dir) implements AutoCloseable {
    @Nullable
    static Snapshot create(Path objectsDir) {
      Path dir = objectsDir.resolveSibling(SNAPSHOT_PREFIX + UUID.randomUUID());
      try {
        return new Snapshot(Files.createDirectory(dir));
      } catch (IOException e) {
        repLog.atSevere().withCause(e).log("Cannot create repair snapshot %s", dir);
        return null;
      }
    }

    boolean linkLooseObjects(Path objectsDir) {
      try (DirectoryStream<Path> fanoutDirs = Files.newDirectoryStream(objectsDir)) {
        for (Path fanoutDir : fanoutDirs) {
          if (fanoutDir.getFileName().toString().length() == 2 && Files.isDirectory(fanoutDir)) {
            linkLooseObjectsIn(fanoutDir);
          }
        }
        return true;
      } catch (IOException e) {
        repLog.atSevere().withCause(e).log("Cannot snapshot loose objects of %s", objectsDir);
        return false;
      }
    }

    private void linkLooseObjectsIn(Path fanoutDir) throws IOException {
      Path snapshotFanoutDir = dir.resolve(fanoutDir.getFileName().toString());
      try (DirectoryStream<Path> objects = Files.newDirectoryStream(fanoutDir)) {
        Files.createDirectory(snapshotFanoutDir);
        for (Path object : objects) {
          if (Files.isRegularFile(object)) {
            link(object, snapshotFanoutDir);
          }
        }
      } catch (NoSuchFileException e) {
        // ignore as objects may vanish
      }
    }

    boolean linkPacks(Path packDir) {
      try (DirectoryStream<Path> packs = Files.newDirectoryStream(packDir, glob(PackExt.PACK))) {
        for (Path pack : packs) {
          PackFile packFile = new PackFile(pack.toFile());
          if (packFile.create(PackExt.INDEX).exists()) {
            linkPackSet(packFile);
          }
        }
        return true;
      } catch (IOException e) {
        repLog.atSevere().withCause(e).log("Cannot snapshot packs of %s", packDir);
        return false;
      }
    }

    private void linkPackSet(PackFile pack) throws IOException {
      if (!link(pack.toPath(), dir)) {
        return;
      }
      if (!link(pack.create(PackExt.INDEX).toPath(), dir)) {
        Files.deleteIfExists(dir.resolve(pack.getName()));
        return;
      }
      link(pack.create(PackExt.BITMAP_INDEX).toPath(), dir);
      link(pack.create(PackExt.REVERSE_INDEX).toPath(), dir);
    }

    private boolean link(Path src, Path destDir) throws IOException {
      try {
        Files.createLink(destDir.resolve(src.getFileName().toString()), src);
        return true;
      } catch (NoSuchFileException e) {
        return false;
      }
    }

    @Override
    public void close() {
      try {
        FileUtils.delete(
            dir.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING | FileUtils.RETRY);
      } catch (IOException e) {
        repLog.atSevere().withCause(e).log("Cannot delete repair snapshot %s", dir);
      }
    }
  }
}
