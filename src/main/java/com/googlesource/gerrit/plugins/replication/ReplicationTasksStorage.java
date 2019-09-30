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
  }

  private static Gson GSON = new Gson();

  private final Path runningUpdates;
  private final Path waitingUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    Path refUpdates = config.getEventsDirectory().resolve("ref-updates");
    runningUpdates = refUpdates.resolve("running");
    waitingUpdates = refUpdates.resolve("waiting");
  }

  public String persist(ReplicateRefUpdate r) {
    String json = GSON.toJson(r) + "\n";
    String eventKey = sha1(json).name();
    Path file = waitingUpdates().resolve(eventKey);

    if (Files.exists(file)) {
      return eventKey;
    }

    try {
      logger.atFine().log("CREATE %s (%s:%s => %s)", file, r.project, r.ref, r.uri);
      Files.write(file, json.getBytes(UTF_8));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Couldn't persist event %s", json);
    }
    return eventKey;
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public void startRunning(ReplicateRefUpdate r) {
    mark(r, true);
  }

  public void abortRunning() {
    ArrayList<ReplicateRefUpdate> result = new ArrayList<>();
    list(result, runningUpdates());
    for (ReplicateRefUpdate r : result) {
      mark(r, false);
    }
  }

  private void mark(ReplicateRefUpdate r, boolean toRunning) {
    String taskJson = GSON.toJson(r) + "\n";
    String taskKey = sha1(taskJson).name();
    Path from = waitingUpdates().resolve(taskKey);
    Path to = runningUpdates().resolve(taskKey);
    if (!toRunning) {
      Path swap = from;
      from = to;
      to = swap;
    }

    try {
      logger.atFine().log("RENAME %s to %s (%s:%s => %s)", from, to, r.project, r.ref, r.uri);
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error while renaming event %s", taskKey);
    }
  }

  public void delete(ReplicateRefUpdate r) {
    String taskJson = GSON.toJson(r) + "\n";
    String taskKey = sha1(taskJson).name();
    Path file = runningUpdates().resolve(taskKey);

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

  public List<ReplicateRefUpdate> list() {
    ArrayList<ReplicateRefUpdate> results = new ArrayList<>();
    list(results, waitingUpdates());
    list(results, runningUpdates());
    return results;
  }

  private void list(ArrayList<ReplicateRefUpdate> results, Path tasks) {
    try (DirectoryStream<Path> events = Files.newDirectoryStream(tasks)) {
      for (Path e : events) {
        if (Files.isRegularFile(e)) {
          String json = new String(Files.readAllBytes(e), UTF_8);
          results.add(GSON.fromJson(json, ReplicateRefUpdate.class));
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error when listing pending events");
    }
  }

  @SuppressWarnings("deprecation")
  private ObjectId sha1(String s) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(s, UTF_8).asBytes());
  }

  private Path runningUpdates() {
    try {
      return Files.createDirectories(runningUpdates);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", runningUpdates), e);
    }
  }

  private Path waitingUpdates() {
    try {
      return Files.createDirectories(waitingUpdates);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", waitingUpdates), e);
    }
  }
}
