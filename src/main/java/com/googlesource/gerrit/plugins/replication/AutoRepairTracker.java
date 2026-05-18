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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  private final ConcurrentMap<DestinationKey, State> statesByDestination =
      new ConcurrentHashMap<>();

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
  public boolean tryBeginRepair(
      Project.NameKey project,
      URIish uri,
      String remoteName,
      UrlDistributionStrategy urlDistributionStrategy) {
    if (!isEnabled()) {
      return false;
    }

    if (!ProjectRepairer.canCopy(uri)) {
      repLog.atWarning().log(
          "Skipping auto-repair for %s to %s: only plain SSH destinations are supported",
          project.get(), uri);
      return false;
    }

    DestinationKey key = DestinationKey.create(project, uri, remoteName, urlDistributionStrategy);
    return statesByDestination.computeIfAbsent(key, k -> new State()).isRepairAllowed(project, uri);
  }

  private record DestinationKey(Project.NameKey project, String destination) {
    static DestinationKey create(
        Project.NameKey project,
        URIish uri,
        String remoteName,
        UrlDistributionStrategy urlDistributionStrategy) {
      return new DestinationKey(
          project,
          urlDistributionStrategy == UrlDistributionStrategy.ROUND_ROBIN
              ? remoteName
              : uri.toString());
    }
  }

  private class State {
    private int repairAttemptCount;
    private Instant lastAttempt = Instant.EPOCH;

    synchronized boolean isRepairAllowed(Project.NameKey project, URIish uri) {
      int maxAttempts = replicationConfig.getAutoRepairMaxAttempts();
      if (repairAttemptCount >= maxAttempts) {
        repLog.atWarning().log(
            "Skipping auto-repair for %s to %s: reached max attempts (%d)",
            project.get(), uri, maxAttempts);
        return false;
      }
      Duration interval = replicationConfig.getAutoRepairInterval();
      Instant now = Instant.now();
      if (!interval.isZero()) {
        Instant nextAllowed = lastAttempt.plus(interval);
        if (now.isBefore(nextAllowed)) {
          repLog.atInfo().log(
              "Skipping auto-repair for %s to %s: interval not elapsed."
                  + " Next attempt allowed at %s",
              project.get(), uri, nextAllowed);
          return false;
        }
      }
      lastAttempt = now;
      repairAttemptCount++;
      return true;
    }
  }
}
