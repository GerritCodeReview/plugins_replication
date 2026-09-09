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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gerrit.entities.Project;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.transport.URIish;

/**
 * URL distribution strategy used when a remote has multiple configured URLs.
 *
 * <p>Each enum constant acts as a factory: call {@link #newInstance()} to obtain a stateful
 * executor. Callers (e.g. {@link Destination}) hold the {@link Instance}, while the enum constant
 * itself remains stateless and safe to use in equality checks.
 *
 * <p>Configured via {@code remote.NAME.urlDistributionStrategy} in {@code replication.config}.
 */
public enum UrlDistributionStrategy {
  /** Push to all configured URLs. */
  ALL("all") {
    @Override
    public Instance newInstance() {
      return (project, candidates) -> candidates;
    }
  },

  /**
   * Push to one URL at a time, rotating through the list on each push event. Particularly useful
   * when multiple replica hosts share a single backend (likely via NFS): pushing to all URLs would
   * cause redundant writes to the same underlying storage, while round-robin distributes load
   * evenly and ensures each push is executed exactly once.
   */
  ROUND_ROBIN("roundRobin") {
    @Override
    public Instance newInstance() {
      final AtomicInteger index = new AtomicInteger();
      return (project, candidates) -> {
        if (candidates.isEmpty()) {
          return List.of();
        }
        return List.of(candidates.get(Math.floorMod(index.getAndIncrement(), candidates.size())));
      };
    }
  },

  /**
   * Push to exactly one URL, chosen by hashing the project name so that a given project always maps
   * to the same URL. Like {@link #ROUND_ROBIN} this writes each push only once, which matters when
   * the replica hosts share a single backend, but it additionally keeps consecutive updates for one
   * project on the same URL. Because replication tasks are coalesced per (project, URI), rotating
   * URLs would let successive updates for one project run as separate tasks racing against the same
   * backend; pinning the project collapses them into a single task and keeps the receiving host's
   * caches warm.
   *
   * <p>The candidates are sorted before indexing so that the mapping does not depend on the order
   * in which the URLs happen to be configured. Together with {@link Project.NameKey#hashCode()},
   * which is the specified {@link String#hashCode()} of the project name, this makes every host
   * reading the same config agree on the mapping.
   */
  PROJECT_SHARDED("projectSharded") {
    @Override
    public Instance newInstance() {
      return (project, candidates) -> {
        if (candidates.isEmpty()) {
          return List.of();
        }
        ImmutableList<URIish> sorted = sortedByUrl(candidates);
        return List.of(sorted.get(Math.floorMod(project.hashCode(), sorted.size())));
      };
    }
  },

  /**
   * Push each ref to exactly one URL, chosen by hashing the ref name, so that a given ref always
   * maps to the same URL while the refs of one project are spread over all URLs. Prefer this over
   * {@link #PROJECT_SHARDED} when a single project dominates the write traffic of the remote, since
   * pinning that project to one URL leaves the remaining URLs idle.
   *
   * <p>Unlike the other single-URL strategies this can turn one push event into several replication
   * tasks, because the refs of one event may hash to different URLs. That gives up the
   * per-(project, URI) task coalescing {@link #PROJECT_SHARDED} relies on; it is the price for
   * spreading a hot project over the URLs, and it is bounded because each ref still only ever goes
   * to one URL.
   *
   * <p>A full sync is requested with the {@code PushOne.ALL_REFS} marker rather than with real ref
   * names, and covers refs belonging to every shard, so it is sent to all candidates.
   *
   * <p>As for {@link #PROJECT_SHARDED} the candidates are sorted before indexing, so the mapping
   * does not depend on the order in which the URLs happen to be configured and every host reading
   * the same config agrees on it.
   */
  REF_SHARDED("refSharded") {
    @Override
    public Instance newInstance() {
      return new Instance() {
        /**
         * Returns every candidate: the refs of a project are spread over all URLs, so all of them
         * hold the project and none can be ruled out without knowing the ref.
         */
        @Override
        public List<URIish> select(Project.NameKey project, List<URIish> candidates) {
          return candidates;
        }

        @Override
        public SetMultimap<URIish, String> distribute(
            Project.NameKey project, Set<String> refNames, List<URIish> candidates) {
          if (candidates.isEmpty() || refNames.contains(PushOne.ALL_REFS)) {
            return everyRefToEachUri(candidates, refNames);
          }
          ImmutableList<URIish> sorted = sortedByUrl(candidates);
          SetMultimap<URIish, String> refsByUri = LinkedHashMultimap.create();
          for (String refName : refNames) {
            refsByUri.put(sorted.get(Math.floorMod(refName.hashCode(), sorted.size())), refName);
          }
          return refsByUri;
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

  /**
   * Sorts the candidates by URL so that hash-based indexing into them does not depend on the order
   * in which the URLs happen to be configured.
   */
  private static ImmutableList<URIish> sortedByUrl(List<URIish> candidates) {
    return ImmutableList.sortedCopyOf(Comparator.comparing(URIish::toString), candidates);
  }

  /** Maps every given URI to the complete set of refs, i.e. does not shard the refs at all. */
  private static SetMultimap<URIish, String> everyRefToEachUri(
      List<URIish> uris, Set<String> refNames) {
    SetMultimap<URIish, String> refsByUri = LinkedHashMultimap.create();
    for (URIish uri : uris) {
      refsByUri.putAll(uri, refNames);
    }
    return refsByUri;
  }

  /** A stateful executor for a {@link UrlDistributionStrategy} strategy. */
  @FunctionalInterface
  public interface Instance {
    /**
     * Selects the URLs to push to out of the candidates for the given project.
     *
     * @param project project being replicated, used by project-affine strategies.
     * @param candidates URLs the project could be pushed to.
     * @return the subset of candidates to push to.
     */
    List<URIish> select(Project.NameKey project, List<URIish> candidates);

    /**
     * Splits the refs of one push event over the candidates.
     *
     * <p>Strategies that decide per project rather than per ref send the whole set of refs to each
     * URL {@link #select} returned; {@link UrlDistributionStrategy#REF_SHARDED} overrides this to
     * give each URL only the refs that hash to it.
     *
     * @param project project being replicated.
     * @param refNames refs updated by the event.
     * @param candidates URLs the project could be pushed to.
     * @return the refs to push, keyed by the URL to push them to.
     */
    default SetMultimap<URIish, String> distribute(
        Project.NameKey project, Set<String> refNames, List<URIish> candidates) {
      return everyRefToEachUri(select(project, candidates), refNames);
    }
  }
}
