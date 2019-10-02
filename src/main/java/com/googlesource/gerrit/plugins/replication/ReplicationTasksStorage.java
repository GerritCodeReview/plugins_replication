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

  private final Path refUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    refUpdates = config.getEventsDirectory().resolve("ref-updates");
  }

  public String persist(ReplicateRefUpdate r) {
    return new Task(r).persist();
  }

  @VisibleForTesting
  public void disableDeleteForTesting(boolean deleteDisabled) {
    this.disableDeleteForTesting = deleteDisabled;
  }

  public void delete(ReplicateRefUpdate r) {
    new Task(r).delete();
  }

  public List<ReplicateRefUpdate> list() {
    ArrayList<ReplicateRefUpdate> result = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(refUpdates())) {
      for (Path e : events) {
        if (Files.isRegularFile(e)) {
          String json = new String(Files.readAllBytes(e), UTF_8);
          result.add(GSON.fromJson(json, ReplicateRefUpdate.class));
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error when firing pending tasks");
    }
    return result;
  }

  @SuppressWarnings("deprecation")
  private ObjectId sha1(String s) {
    return ObjectId.fromRaw(Hashing.sha1().hashString(s, UTF_8).asBytes());
  }

  private Path refUpdates() {
    try {
      return Files.createDirectories(refUpdates);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", refUpdates), e);
    }
  }

  private class Task {
    public final ReplicateRefUpdate update;
    public final String json;
    public final String taskKey;
    public final Path file;

    public Task(ReplicateRefUpdate update) {
      this.update = update;
      json = GSON.toJson(update) + "\n";
      taskKey = sha1(json).name();
      file = refUpdates().resolve(taskKey);
    }

    public String persist() {
      if (Files.exists(file)) {
        return taskKey;
      }

      try {
        logger.atFine().log("CREATE %s %s", file, updateLog());
        Files.write(file, json.getBytes(UTF_8));
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Couldn't persist task %s", json);
      }
      return taskKey;
    }

    public void delete() {
      if (disableDeleteForTesting) {
        logger.atFine().log("DELETE %s %s DISABLED", file, updateLog());
        return;
      }

      try {
        logger.atFine().log("DELETE %s %s", file, updateLog());
        Files.delete(file);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while deleting task %s", taskKey);
      }
    }

    private String updateLog() {
      return String.format("(%s:%s => %s)", update.project, update.ref, update.uri);
    }
  }
}
