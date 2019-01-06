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

@Singleton
public class EventsStorage {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static class ReplicateRefUpdate {
    public String project;
    public String ref;

    @Override
    public String toString() {
      return "ref-update " + project + ":" + ref;
    }
  }

  private static Gson GSON = new Gson();

  private final Path refUpdates;

  @Inject
  EventsStorage(ReplicationConfig config) {
    refUpdates = config.getEventsDirectory().resolve("ref-updates");
  }

  public String persist(String project, String ref) {
    ReplicateRefUpdate r = new ReplicateRefUpdate();
    r.project = project;
    r.ref = ref;

    String json = GSON.toJson(r) + "\n";
    String eventKey = sha1(json).name();
    Path file = refUpdates().resolve(eventKey);

    if (Files.exists(file)) {
      return eventKey;
    }

    try {
      Files.write(file, json.getBytes(UTF_8));
    } catch (IOException e) {
      logger.atWarning().log("Couldn't persist event %s", json);
    }
    return eventKey;
  }

  public void delete(String eventKey) {
    if (eventKey != null) {
      try {
        Files.delete(refUpdates().resolve(eventKey));
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error while deleting event %s", eventKey);
      }
    }
  }

  public List<ReplicateRefUpdate> list() {
    ArrayList<ReplicateRefUpdate> result = new ArrayList<>();
    try (DirectoryStream<Path> events = Files.newDirectoryStream(refUpdates())) {
      for (Path e : events) {
        if (Files.isRegularFile(e)) {
          String json = new String(Files.readAllBytes(e), UTF_8);
          result.add(GSON.fromJson(json, ReplicateRefUpdate.class));
          Files.delete(e);
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error when firing pending events");
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
}
