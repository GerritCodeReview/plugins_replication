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
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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
 *   .../building/<tmp_name>                              new replication tasks under construction
 *   .../running/<sha1(uri)>/<ownerId>/<sha1(task)>       running replication tasks
 *   .../waiting/<sha1(task)>                             outstanding replication tasks
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

    private String sha1() {
      String key = project + "\n" + ref + "\n" + uri + "\n" + remote;
      return ReplicationTasksStorage.sha1(key);
    }
  }

  private static final Gson GSON = new Gson();

  private final Path buildingUpdates;
  private final Path runningUpdates;
  private final Path waitingUpdates;
  private final String ownerId;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    this(config.getEventsDirectory().resolve("ref-updates"));
  }

  @VisibleForTesting
  public ReplicationTasksStorage(Path refUpdates) {
    buildingUpdates = refUpdates.resolve("building");
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
    ownerId = getOwnerId();
  }

  protected static String getOwnerId() {
    String uuid = UUID.randomUUID().toString();
    String vmInstanceName = ManagementFactory.getRuntimeMXBean().getName(); // Usually pid@hostname
    return vmInstanceName + "_" + uuid;
  }

  public synchronized String create(ReplicateRefUpdate r) {
    return new Task(r).create();
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public synchronized void start(UriUpdates uriUpdates) {
    for (ReplicateRefUpdate update : uriUpdates.getReplicateRefUpdates()) {
      new Task(update).start();
    }
  }

  public synchronized void reset(UriUpdates uriUpdates) {
    for (ReplicateRefUpdate update : uriUpdates.getReplicateRefUpdates()) {
      new Task(update).reset();
    }
  }

  public synchronized void resetAll() {
    for (Task t : runningTasks()) {
      t.reset();
    }
  }

  public synchronized void finish(UriUpdates uriUpdates) {
    for (ReplicateRefUpdate update : uriUpdates.getReplicateRefUpdates()) {
      new Task(update).finish();
    }
  }

  public List<ReplicateRefUpdate> listWaiting() {
    return listRefUpdates(createDir(waitingUpdates));
  }

  public List<ReplicateRefUpdate> listRunning() {
    return listRefUpdates(createDir(runningUpdates));
  }

  public List<Task> runningTasks() {
    return list(createDir(runningUpdates));
  }

  private List<ReplicateRefUpdate> listRefUpdates(Path dir) {
    return list(dir).stream().map(t -> t.update).collect(Collectors.toList());
  }

  private List<Task> list(Path tasks) {
    List<Task> results = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(tasks)) {
      for (Path path : events) {
        if (Files.isRegularFile(path)) {
          try {
            String json = new String(Files.readAllBytes(path), UTF_8);
            results.add(new Task(GSON.fromJson(json, ReplicateRefUpdate.class), path));
          } catch (NoSuchFileException ex) {
            logger.atFine().log(
                "File %s not found while listing waiting tasks (likely in-flight or completed by another node)",
                path);
          } catch (IOException e) {
            logger.atSevere().withCause(e).log("Error when firing pending event %s", path);
          }
        } else if (Files.isDirectory(path)) {
          try {
            results.addAll(list(path));
          } catch (DirectoryIteratorException d) {
            // iterating over the sub-directories is expected to have dirs disappear
            Nfs.throwIfNotStaleFileHandle(d.getCause());
          }
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while listing tasks");
    }
    return results;
  }

  @SuppressWarnings("deprecation")
  private static String sha1(String s) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(s, UTF_8).asBytes()).name();
  }

  private static Path createDir(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", dir), e);
    }
  }

  @VisibleForTesting
  class Task {
    public final ReplicateRefUpdate update;
    public final String json;
    public final String taskKey;
    public final String uriKey;
    public final Path running;
    public final Path waiting;

    public Task(ReplicateRefUpdate update) {
      this(update, ownerId);
    }

    public Task(ReplicateRefUpdate update, String ownerId) {
      this.update = update;
      json = GSON.toJson(update) + "\n";
      taskKey = update.sha1();
      uriKey = sha1(update.uri);
      running = createDir(runningUpdates.resolve(uriKey).resolve(ownerId)).resolve(taskKey);
      waiting = createDir(waitingUpdates).resolve(taskKey);
    }

    public Task(ReplicateRefUpdate update, Path taskFile) {
      this(update, taskFile.getParent().getFileName().toString());
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
      if (logger.atFine().isEnabled()) {
        if (Files.exists(running.getParent())) {
          for (Task t : list(running.getParent())) {
            if (t.update.ref.equals(this.update.ref)) {
              logger.atFine().log(
                  "A Task with key %s with ref: %s is already running on same node",
                  t.taskKey, t.update);
            }
          }
        }
      }
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
        try {
          Files.deleteIfExists(running.getParent());
          Files.deleteIfExists(running.getParent().getParent());
        } catch (Exception e) {
          logger.atFine().log("Error cleaning up finished task %s", taskKey);
        }
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
