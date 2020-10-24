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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;

/**
 * A persistent store for replication tasks.
 *
 * <p>The data of this store lives under <replication_data>/ref-updates where replication_data is
 * determined by the replication.eventsDirectory config option and defaults to
 * <site_dir>/data/replication. Atomic renames must be supported from anywhere within the store to
 * anywhere within the store. This generally means that all the contents of the store needs to live
 * on the same filesystem.
 *
 * <p>Individual tasks are stored in files under the following directories using the sha1 of the
 * task:
 *
 * <p><code>
 *   .../running/<sha1>    running replication tasks
 *   .../waiting/<sha1>    outstanding replication tasks
 * </code>
 *
 * <p>Tasks are moved atomically via a rename between those directories to indicate the current
 * state of each task.
 */
@Singleton
public class ReplicationTasksStorage {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private boolean disableDeleteForTesting;

  public static class ReplicateRefUpdate {
    public final String project;
    public final String ref;
    public final String uri;
    public final String remote;

    public ReplicateRefUpdate(PushOne push, String ref) {
      this(push.getProjectNameKey().get(), ref, push.getURI(), push.getRemoteName());
    }

    public ReplicateRefUpdate(String project, String ref, URIish uri, String remote) {
      this.project = project;
      this.ref = ref;
      this.uri = uri.toASCIIString();
      this.remote = remote;
    }

    @Override
    public String toString() {
      return "ref-update " + project + ":" + ref + " uri:" + uri + " remote:" + remote;
    }
  }

  private static final Gson GSON = new Gson();

  private final Path runningUpdates;
  private final Path waitingUpdates;
  private final Path refUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    this(config.getEventsDirectory().resolve("ref-updates"));
  }

  @VisibleForTesting
  ReplicationTasksStorage(Path refUpdates) {
    this.refUpdates = refUpdates;
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
  }

  public String persist(ReplicateRefUpdate r) {
    //
    return new Task(r).persist();
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public void delete(ReplicateRefUpdate r) {
    String key = r.project + "\n" + r.ref + "\n" + r.uri + "\n" + r.remote;
    String taskKey = sha1(key).name();
    Path file = waitingUpdates.resolve(taskKey);

    if (disableDeleteForTesting) {
      logger.atFine().log("DELETE %s (%s:%s => %s) DISABLED", file, r.project, r.ref, r.uri);
      return;
    }

    try {
      logger.atFine().log("DELETE %s (%s:%s => %s)", file, r.project, r.ref, r.uri);
      Files.delete(file);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while deleting event %s", taskKey);
    }
  }

  public void start(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).start();
    }
  }

  public void reset(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).reset();
    }
  }

  public void resetAll() {
    for (ReplicateRefUpdate r : list(createDir(runningUpdates))) {
      new Task(r).reset();
    }
  }

  public void finish(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).finish();
      // >>>>>>> 48ff6f3... Fix potential loss of persisted replication task
    }
  }

  public List<ReplicateRefUpdate> listWaiting() {
    return list(createDir(waitingUpdates));
  }

  public List<ReplicateRefUpdate> list() {
    List<ReplicateRefUpdate> results = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(refUpdates)) {
      for (Path path : events) {
        results.addAll(list(path));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return results;
  }

  private List<ReplicateRefUpdate> list(Path tasks) {
    List<ReplicateRefUpdate> results = new ArrayList<>();
    // <<<<<<< HEAD
    //    try (DirectoryStream<Path> events = Files.newDirectoryStream(refUpdates())) {
    //      for (Path path : events) {
    //        if (Files.isRegularFile(path)) {
    //          try {
    //            String json = new String(Files.readAllBytes(path), UTF_8);
    //            results.add(GSON.fromJson(json, ReplicateRefUpdate.class));
    //          } catch (NoSuchFileException ex) {
    //            logger.atFine().log(
    //                "File %s not found while listing waiting tasks (likely in-flight or completed
    // by another node)",
    //                path);
    //          } catch (IOException e) {
    //            logger.atSevere().withCause(e).log("Error when firing pending event %s", path);
    //          }
    //        }
    //      }
    //    } catch (IOException e) {
    //      logger.atSevere().withCause(e).log("Error when firing pending events");
    // =======
    try (DirectoryStream<Path> events = Files.newDirectoryStream(tasks)) {
      for (Path e : events) {
        if (Files.isRegularFile(e)) {
          String json = new String(Files.readAllBytes(e), UTF_8);
          results.add(GSON.fromJson(json, ReplicateRefUpdate.class));
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while listing tasks");
      // >>>>>>> 48ff6f3... Fix potential loss of persisted replication task
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

  private class Task {
    public final ReplicateRefUpdate update;
    public final String json;
    public final String taskKey;
    public final Path running;
    public final Path waiting;

    public Task(ReplicateRefUpdate update) {
      this.update = update;
      json = GSON.toJson(update) + "\n";
      String key = update.project + "\n" + update.ref + "\n" + update.uri + "\n" + update.remote;
      taskKey = sha1(key).name();
      running = createDir(runningUpdates).resolve(taskKey);
      waiting = createDir(waitingUpdates).resolve(taskKey);
    }

    public String persist() {
      if (Files.exists(waiting)) {
        return taskKey;
      }

      try {
        logger.atFine().log("CREATE %s %s", waiting, updateLog());
        Files.write(waiting, json.getBytes(UTF_8));
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Couldn't persist task %s", json);
      }
      return taskKey;
    }

    public void start() {
      rename(waiting, running);
    }

    public void reset() {
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
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while deleting task %s", taskKey);
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
