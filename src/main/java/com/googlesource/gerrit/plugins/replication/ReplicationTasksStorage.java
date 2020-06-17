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
import static java.util.stream.Collectors.toList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *   .../building/<tmp_name>  new replication tasks under construction
 *   .../running/<sha1>       running replication tasks
 *   .../waiting/<sha1>       outstanding replication tasks
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

  private final Path refUpdates;
  private final Path buildingUpdates;
  private final Path runningUpdates;
  private final Path waitingUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    refUpdates = config.getEventsDirectory().resolve("ref-updates");
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

  public synchronized void start(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).start();
    }
  }

  public synchronized void reset(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).reset();
    }
  }

  public synchronized void resetAll() {
    for (ReplicateRefUpdate r : listRunning()) {
      new Task(r).reset();
    }
  }

  public synchronized void finish(PushOne push) {
    for (String ref : push.getRefs()) {
      new Task(new ReplicateRefUpdate(push, ref)).finish();
    }
  }

  public synchronized List<ReplicateRefUpdate> listWaiting() {
    return list(createDir(waitingUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> listRunning() {
    return list(createDir(runningUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> listBuilding() {
    return list(createDir(buildingUpdates));
  }

  @VisibleForTesting
  public synchronized List<ReplicateRefUpdate> list() {
    return list(createDir(refUpdates));
  }

  private List<ReplicateRefUpdate> list(Path tasks) {
    return stream(tasks).collect(toList());
  }

  public Stream<ReplicateRefUpdate> streamWaiting() {
    return stream(createDir(waitingUpdates));
  }

  private Stream<ReplicateRefUpdate> stream(Path tasks) {
    return walk(tasks)
        .map(path -> getReplicateRefUpdate(path))
        .filter(optional -> optional.isPresent())
        .map(optional -> optional.get());
  }

  private Stream<Path> walk(Path path) {
    try {
      return Stream.concat(Stream.of(path), Files.list(path).flatMap(sub -> walk(sub)));
    } catch (NotDirectoryException e) {
      return Stream.of(path);
    } catch (Exception e) {
      handle(path, e);
    }
    return Stream.empty();
  }

  private Optional<ReplicateRefUpdate> getReplicateRefUpdate(Path file) {
    try {
      String json = new String(Files.readAllBytes(file), UTF_8);
      return Optional.of(GSON.fromJson(json, ReplicateRefUpdate.class));
    } catch (Exception e) {
      if (!(e instanceof IOException && e.getMessage().equals("Is a directory"))) {
        handle(file, e);
      }
    }
    return Optional.empty();
  }

  private void handle(Path path, Exception e) {
    if (e instanceof NoSuchFileException) {
      logger.atFine().log(
          "File %s not found while listing waiting tasks (likely in-flight or completed by another node)",
          path);
    } else {
      logger.atSevere().withCause(e).log("Error when firing pending event %s", path);
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
