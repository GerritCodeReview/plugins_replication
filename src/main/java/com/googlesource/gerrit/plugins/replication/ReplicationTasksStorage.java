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
import java.util.Optional;
import java.util.stream.Stream;
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

  private static final Gson GSON = new Gson();

  private final Path refUpdates;

  @Inject
  ReplicationTasksStorage(ReplicationConfig config) {
    refUpdates = config.getEventsDirectory().resolve("ref-updates");
  }

  public String persist(ReplicateRefUpdate r) {
    String json = GSON.toJson(r) + "\n";
    String key = r.project + "\n" + r.ref + "\n" + r.uri + "\n" + r.remote;
    String eventKey = sha1(key).name();
    Path file = refUpdates().resolve(eventKey);

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

  public void delete(ReplicateRefUpdate r) {
    String key = r.project + "\n" + r.ref + "\n" + r.uri + "\n" + r.remote;
    String taskKey = sha1(key).name();
    Path file = refUpdates().resolve(taskKey);

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

  public Stream<ReplicateRefUpdate> stream() {
    return walk(refUpdates())
        .map(path -> getReplicateRefUpdate(path))
        .filter(Optional::isPresent)
        .map(Optional::get);
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

  private Path refUpdates() {
    try {
      return Files.createDirectories(refUpdates);
    } catch (IOException e) {
      throw new ProvisionException(String.format("Couldn't create %s", refUpdates), e);
    }
  }
}
