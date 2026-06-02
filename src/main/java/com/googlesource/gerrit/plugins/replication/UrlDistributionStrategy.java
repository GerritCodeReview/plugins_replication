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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.transport.URIish;

/**
 * URL distribution strategy used when a remote has multiple configured URLs.
 *
 * <p>Each enum constant acts as a factory: call {@link #newInstance()} to obtain a stateful
 * executor. Callers (e.g. {@link Destination}) hold the {@link Instance}, while the enum constant
 * itself remains stateless and safe to use in equality checks.
 *
 * <p>Configured via {@code remote.NAME.urlDistribution} in {@code replication.config}.
 */
public enum UrlDistributionStrategy {
  /** Push to all configured URLs. */
  ALL("all") {
    @Override
    public Instance newInstance() {
      return candidates -> candidates;
    }
  },

  /**
   * Push to one URL at a time, rotating through the list on each push event. Particularly useful
   * when multiple replica hosts share a single backend (likely via NFS): pushing to all URLs would
   * cause redundant writes to the same underlying storage, while round-robin distributes load
   * evenly and ensures each push is executed exactly once. On transport failure {@link
   * Instance#failover} hands the push over to the next URL in the rotation.
   */
  ROUND_ROBIN("roundRobin") {
    @Override
    public Instance newInstance() {
      return new Instance() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public List<URIish> select(List<URIish> candidates) {
          if (candidates.isEmpty()) {
            return List.of();
          }
          return List.of(candidates.get(Math.floorMod(index.getAndIncrement(), candidates.size())));
        }

        @Override
        public URIish failover(List<URIish> candidates, URIish failed) {
          if (candidates.size() < 2) {
            return failed;
          }
          for (int attempt = 0; attempt < candidates.size(); attempt++) {
            URIish next = candidates.get(Math.floorMod(index.getAndIncrement(), candidates.size()));
            if (!next.equals(failed)) {
              return next;
            }
          }
          return failed;
        }
      };
    }
  };

  public final String configKey;

  UrlDistributionStrategy(String key) {
    configKey = key;
  }

  /** Creates a new stateful executor for this distribution strategy. */
  public abstract Instance newInstance();

  /**
   * Returns the distribution strategy for the given config value, or {@link #ALL} if the value is
   * unrecognized or absent.
   */
  public static UrlDistributionStrategy fromConfig(String value) {
    return Arrays.stream(values())
        .filter(candidate -> candidate.configKey.equals(value))
        .findFirst()
        .orElse(ALL);
  }

  /** A stateful executor for a {@link UrlDistributionStrategy} strategy. */
  @FunctionalInterface
  public interface Instance {
    /** Select the URLs to push to for this scheduling event. */
    List<URIish> select(List<URIish> candidates);

    /**
     * If a push to any URI returned by {@link #select(List)} fails, {@link #failover(List,
     * URIish)}} is invoked to select the next URI for retry.
     */
    default URIish failover(List<URIish> candidates, URIish failed) {
      return failed;
    }
  }
}
