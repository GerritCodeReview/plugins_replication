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

import com.google.common.flogger.FluentLogger;
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
public enum UrlDistribution {

  /**
   * Push to all configured URLs simultaneously. This is the default behaviour and matches the
   * pre-existing replication semantics.
   *
   * <p>Config value: {@code ALL}
   */
  ALL {
    @Override
    public Instance newInstance() {
      return candidates -> candidates;
    }
  },

  /**
   * Push to one URL at a time, rotating through the list on each push event. Particularly useful
   * when multiple replica hosts share a single NFS backend: pushing to all URLs simultaneously
   * would cause redundant writes to the same underlying storage, while round-robin distributes
   * load evenly and ensures each push is written exactly once.
   *
   * <p>Config values: {@code RR}, {@code RoundRobin}
   */
  ROUND_ROBIN {
    @Override
    public Instance newInstance() {
      final AtomicInteger index = new AtomicInteger();
      return candidates -> {
        if (candidates.isEmpty()) {
          return List.of();
        }
        return List.of(candidates.get(Math.floorMod(index.getAndIncrement(), candidates.size())));
      };
    }
  };

  /** Creates a new stateful executor for this distribution strategy. */
  public abstract Instance newInstance();

  /**
   * Returns the distribution strategy for the given config value, or {@link #ALL} if the value is
   * unrecognised or absent.
   *
   * <p>Accepted values (case-insensitive): {@code ALL}, {@code RR}, {@code RoundRobin}.
   */
  public static UrlDistribution fromConfig(String value) {
    return switch (value) {
      case "RR", "RoundRobin" -> ROUND_ROBIN;
      default -> ALL;
    };
  }

  /** A stateful executor for a {@link UrlDistribution} strategy. */
  @FunctionalInterface
  public interface Instance {
    List<URIish> select(List<URIish> candidates);
  }
}
