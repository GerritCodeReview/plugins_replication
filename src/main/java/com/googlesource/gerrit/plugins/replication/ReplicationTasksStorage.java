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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 *   .../building/<tmp_name>                       new replication tasks under construction
 *   .../running/<sha1>                            running replication tasks
 *   .../waiting/<task_sha1_NN_shard>/<task_sha1>  outstanding replication tasks
 * </code>
 *
 * <p>Tasks are moved atomically via a rename between those directories to indicate the current
 * state of each task.
 *
 * <p>Note: The .../waiting/<task_sha1_NN_shard> directories are never removed. This helps prevent
 * failures when moving tasks to and from the shard directories from different hosts concurrently.
 */
@Singleton
public class ReplicationTasksStorage {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private boolean disableDeleteForTesting;

  public static class ReplicateRefUpdate {
    public static Optional<ReplicateRefUpdate> createOptionally(Path file) {
      try {
        return Optional.of(create(file));
      } catch (NoSuchFileException e) {
        logger.atFine().log("File %s not found while reading task", file);
      } catch (IOException e) {
        if (!e.getMessage().equals("Is a directory")) {
          logger.atSevere().withCause(e).log("Error while reading task %", file);
        }
      }
      return Optional.empty();
    }

    public static ReplicateRefUpdate create(Path file) throws IOException {
      String json = new String(Files.readAllBytes(file), UTF_8);
      return GSON.fromJson(json, ReplicateRefUpdate.class);
    }

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
  }

  private static final Gson GSON = new Gson();

  private final Path buildingUpdates;
  private final Path runningUpdates;
  private final Path waitingUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    this(config.getEventsDirectory().resolve("ref-updates"));
  }

  @VisibleForTesting
  public ReplicationTasksStorage(Path refUpdates) {
    buildingUpdates = refUpdates.resolve("building");
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
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
    for (ReplicateRefUpdate r : list(createDir(runningUpdates))) {
      new Task(r).reset();
    }
  }

  public boolean isWaiting(UriUpdates uriUpdates) {
    return uriUpdates.getReplicateRefUpdates().stream()
        .map(update -> new Task(update))
        .anyMatch(Task::isWaiting);
  }

  public void finish(UriUpdates uriUpdates) {
    for (ReplicateRefUpdate update : uriUpdates.getReplicateRefUpdates()) {
      new Task(update).finish();
    }
  }

  public List<ReplicateRefUpdate> listWaiting() {
    return list(createDir(waitingUpdates));
  }

  public List<ReplicateRefUpdate> listRunning() {
    return list(createDir(runningUpdates));
  }

  private List<ReplicateRefUpdate> list(Path taskDir) {
    return streamRecursive(taskDir).collect(Collectors.toList());
  }

  private Stream<ReplicateRefUpdate> streamRecursive(Path dir) {
    return walk(dir)
        .map(path -> ReplicateRefUpdate.createOptionally(path))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  private static Stream<Path> walk(Path path) {
    try {
      return Stream.concat(Stream.of(path), Files.list(path).flatMap(sub -> walk(sub)));
    } catch (NotDirectoryException e) {
      return Stream.of(path);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while walking directory %", path);
      return Stream.empty();
    }
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

  @VisibleForTesting
  class Task {
    public final ReplicateRefUpdate update;
    public final String taskKey;
    public final Path running;
    public final Path waiting;

    public Task(ReplicateRefUpdate update) {
      this.update = update;
      String key = update.project + "\n" + update.ref + "\n" + update.uri + "\n" + update.remote;
      taskKey = sha1(key).name();
      running = createDir(runningUpdates).resolve(taskKey);
      waiting = createDir(waitingUpdates).resolve(taskKey);
    }

    public String create() {
      if (Files.exists(waiting)) {
        return taskKey;
      }

      String json = GSON.toJson(update) + "\n";
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

    public void reset() {
      rename(running, waiting);
    }

    public boolean isWaiting() {
      return Files.exists(waiting);
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
