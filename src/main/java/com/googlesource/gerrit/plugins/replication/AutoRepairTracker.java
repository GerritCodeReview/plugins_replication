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

import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.transport.URIish;

/**
 * Tracks per-project, per-destination auto-repair attempts and enforces interval and attempt
 * limits.
 *
 * <p>State is kept in memory and is reset whenever the plugin is reloaded or Gerrit restarts.
 */
@Singleton
public class AutoRepairTracker {
  private final ReplicationConfig replicationConfig;
  private final ConcurrentMap<Key, State> states = new ConcurrentHashMap<>();

  @Inject
  AutoRepairTracker(ReplicationConfig replicationConfig) {
    this.replicationConfig = replicationConfig;
  }

  public boolean isEnabled() {
    return replicationConfig.getAutoRepairMaxAttempts() > 0;
  }

  /**
   * Returns whether a new auto-repair attempt is allowed for the project on the given destination.
   *
   * <p>If allowed, records the attempt before returning {@code true}.
   */
  public boolean tryBeginRepair(Project.NameKey project, URIish uri) {
    if (!isEnabled()) {
      return false;
    }

    if (!ProjectRepairer.canCopy(uri)) {
      repLog.atWarning().log(
          "Skipping auto-repair for %s to %s: only plain SSH destinations are supported",
          project.get(), uri);
      return false;
    }

    long now = System.currentTimeMillis();
    int intervalDays = replicationConfig.getAutoRepairIntervalDays();
    long intervalMs = intervalDays > 0 ? TimeUnit.DAYS.toMillis(intervalDays) : 0;
    int maxAttempts = replicationConfig.getAutoRepairMaxAttempts();

    Key key = new Key(project, uri);
    State current = states.get(key);
    int currentCount = 0;
    if (current != null) {
      currentCount = current.repairAttemptCount;
      if (currentCount >= maxAttempts) {
        repLog.atWarning().log(
            "Skipping auto-repair for %s to %s: reached max attempts (%d/%d)",
            project.get(), uri, currentCount, maxAttempts);
        return false;
      }
      long remainingMs = intervalMs - (now - current.repairStartTimestamp);
      if (intervalMs > 0 && remainingMs > 0) {
        double remainingHours = remainingMs / (double) TimeUnit.HOURS.toMillis(1);
        repLog.atInfo().log(
            "Skipping auto-repair for %s to %s: interval not elapsed. Next attempt allowed in %.1f"
                + " hours",
            project.get(), uri, remainingHours);
        return false;
      }
    }

    states.put(key, new State(currentCount + 1, now));
    return true;
  }

  private record Key(Project.NameKey project, URIish uri) {}

  private record State(int repairAttemptCount, long repairStartTimestamp) {}
}
