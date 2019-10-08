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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class ReplicationTasksStorage {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private boolean disableDeleteForTesting;

  public static class ReplicateRefUpdate extends UriUpdate {
    public final String ref;

    public ReplicateRefUpdate(UriUpdate update, String ref) {
      this(update.project, ref, update.uri, update.remote);
    }

    public ReplicateRefUpdate(String project, String ref, URIish uri, String remote) {
      this(project, ref, uri.toASCIIString(), remote);
    }

    protected ReplicateRefUpdate(String project, String ref, String uri, String remote) {
      super(project, uri, remote);
      this.ref = ref;
    }

    @Override
    public String toString() {
      return "ref-update " + project + ":" + ref + " uri:" + uri + " remote:" + remote;
    }
  }

  public static class UriUpdate {
    public final String project;
    public final String uri;
    public final String remote;

    public UriUpdate(PushOne push) {
      this(push.getProjectNameKey().get(), push.getURI(), push.getRemoteName());
    }

    public UriUpdate(String project, URIish uri, String remote) {
      this(project, uri.toASCIIString(), remote);
    }

    public UriUpdate(String project, String uri, String remote) {
      this.project = project;
      this.uri = uri;
      this.remote = remote;
    }

    @Override
    public String toString() {
      return "ref-update " + project + " uri:" + uri + " remote:" + remote;
    }
  }

  private static Gson GSON = new Gson();

  private final Path buildingUpdates;
  private final Path runningUpdates;
  private final Path waitingUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    Path refUpdates = config.getEventsDirectory().resolve("ref-updates");
    buildingUpdates = refUpdates.resolve("building");
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
  }

  public String create(ReplicateRefUpdate r) {
    return new Task(r).create();
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public boolean start(PushOne push) {
    UriLock lock = new UriLock(push);
    if (!lock.start()) {
      return false;
    }

    for (String ref : push.getRefs()) {
      new Task(lock, ref).start();
    }
    return true;
  }

  public void abort(PushOne push) {
    UriLock lock = new UriLock(push);
    for (String ref : push.getRefs()) {
      new Task(lock, ref).abort();
    }
    lock.abort();
  }

  public void abortAll() {
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(createDir(runningUpdates))) {
      for (Path dir : dirs) {
        UriLock lock = null;
        for (ReplicateRefUpdate u : list(dir)) {
          if (lock == null) {
            lock = new UriLock(u);
          }
          new Task(u).abort();
        }
        if (lock != null) {
          lock.abort();
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error when aborting running tasks");
    }
  }

  public void finish(PushOne push) {
    UriLock lock = new UriLock(push);
    for (ReplicateRefUpdate r : list(lock.runningDir)) {
      new Task(lock, r.ref).finish();
    }
    lock.finish();
  }

  public List<ReplicateRefUpdate> listWaiting() {
    return list(createDir(waitingUpdates));
  }

  private List<ReplicateRefUpdate> listSubs(Path parent) {
    List<ReplicateRefUpdate> results = new ArrayList<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(parent)) {
      for (Path dir : dirs) {
        results.addAll(list(dir));
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error when listing tasks");
    }
    return results;
  }

  private List<ReplicateRefUpdate> list(Path tasks) {
    List<ReplicateRefUpdate> results = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(tasks)) {
      for (Path e : events) {
        if (Files.isRegularFile(e)) {
          String json = new String(Files.readAllBytes(e), UTF_8);
          results.add(GSON.fromJson(json, ReplicateRefUpdate.class));
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while listing tasks");
    }
    return results;
  }

  @SuppressWarnings("deprecation")
  private ObjectId sha1(String s) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(s, UTF_8).asBytes());
  }

  private static Path createDir(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", dir), e);
    }
  }

  /** Try to delete a path. Do NOT throw IOExceptions. */
  private static void tryDelete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
    }
  }

  private class UriLock {
    public final UriUpdate update;
    public final String uriKey;
    public final Path runningDir;

    public UriLock(PushOne push) {
      this(new UriUpdate(push));
    }

    public UriLock(UriUpdate update) {
      this.update = update;
      uriKey = sha1(update.uri).name();
      runningDir = createDir(runningUpdates).resolve(uriKey);
    }

    public boolean start() {
      try {
        logger.atFine().log("MKDIR %s %s", runningDir, updateLog());
        Files.createDirectory(runningDir);
        return true;
      } catch (FileAlreadyExistsException e) {
        return false; // already running
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while starting uri %s", uriKey);
        return true; // safer to risk a duplicate then to skip it
      }
    }

    public void abort() {
      tryDelete(runningDir);
    }

    public void finish() {
      if (disableDeleteForTesting) {
        logger.atFine().log("DELETE %s %s DISABLED", runningDir, updateLog());
        return;
      }

      try {
        logger.atFine().log("DELETE %s %s", runningDir, updateLog());
        Files.delete(runningDir);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while finishing uri %s", uriKey);
      }
    }

    private String updateLog() {
      return String.format("(%s => %s)", update.project, update.uri);
    }
  }

  private class Task {
    public final ReplicateRefUpdate update;
    public final String json;
    public final String taskKey;
    public final Path running;
    public final Path waiting;

    public Task(ReplicateRefUpdate r) {
      this(new UriLock(r), r.ref);
    }

    public Task(UriLock lock, String ref) {
      update = new ReplicateRefUpdate(lock.update, ref);
      json = GSON.toJson(update) + "\n";
      String key = update.project + "\n" + update.ref + "\n" + update.uri + "\n" + update.remote;
      taskKey = sha1(key).name();
      running = lock.runningDir.resolve(taskKey);
      waiting = createDir(waitingUpdates).resolve(taskKey);
    }

    public String create() {
      if (Files.exists(waiting)) {
        return taskKey;
      }

      try {
        Path tmp = Files.createTempFile(createDir(buildingUpdates), taskKey, null);
        logger.atFine().log("CREATE %s %s", tmp, updateLog());
        Files.write(tmp, json.getBytes(UTF_8));
        logger.atFine().log("RENAME %s %s %s", tmp, waiting, updateLog());
        rename(tmp, waiting);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Couldn't create task %s", json);
      }
      return taskKey;
    }

    public void start() {
      rename(waiting, running);
    }

    public void abort() {
      rename(running, waiting);
    }

    public void finish() {
      if (disableDeleteForTesting) {
        logger.atFine().log("DELETE %s %s DISABLED", running, updateLog());
        return;
      }

      try {
        logger.atFine().log("DELETE %s %s", running, updateLog());
        Files.delete(running);
      } catch (NoSuchFileException e) { // expected after an abortRunning()
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while finishing task %s", taskKey);
      }
    }

    private void rename(Path from, Path to) {
      try {
        logger.atFine().log("RENAME %s to %s %s", from, to, updateLog());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while renaming task %s", taskKey);
      }
    }

    private String updateLog() {
      return String.format("(%s:%s => %s)", update.project, update.ref, update.uri);
    }
  }
}
