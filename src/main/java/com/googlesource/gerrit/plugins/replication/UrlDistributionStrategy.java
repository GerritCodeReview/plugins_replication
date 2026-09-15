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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
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
   * evenly and ensures each push is executed exactly once. On transport failure {@link
   * Instance#failover} hands the push over to the next URL in the rotation.
   */
  ROUND_ROBIN("roundRobin") {
    @Override
    public Instance newInstance() {
      return new Instance() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public List<URIish> urlsToReplicateTo(NameKey project, List<URIish> candidates) {
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
      return new Instance() {
        @Override
        public List<URIish> urlsToReplicateTo(NameKey project, List<URIish> candidates) {
          if (candidates.isEmpty()) {
            return List.of();
          }
          ImmutableList<URIish> sorted = sortedByUrl(candidates);
          return List.of(sorted.get(Math.floorMod(project.hashCode(), sorted.size())));
        }

        @Override
        public URIish failover(List<URIish> candidates, URIish failed) {
          if (candidates.size() < 2) {
            return failed;
          }
          return nextAfter(candidates, failed);
        }
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
   * per-(project, URI) task coalescing {@link #PROJECT_SHARDED} relies on, and refs that were
   * updated together, such as the patch set ref and the NoteDb meta ref of a change, may be split
   * over separate pushes that do not land at the same time. It is the price for spreading a hot
   * project over the URLs, and it is bounded because each ref still only ever goes to one URL.
   *
   * <p>A full sync is requested with the {@code PushOne.ALL_REFS} marker rather than with real ref
   * names, and covers refs belonging to every shard, so it is sent to all candidates. Each of those
   * pushes then keeps only the refs it owns, see {@link Instance#refsOwnedBy}, so an out-of-date
   * ref is still pushed by exactly one of them.
   *
   * <p>As for {@link #PROJECT_SHARDED} the candidates are sorted before indexing, so the mapping
   * does not depend on the order in which the URLs happen to be configured and every host reading
   * the same config agrees on it. On transport failure {@link Instance#failover} hands the refs of
   * the failed push over to the next URL.
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
        public List<URIish> urlsToReplicateTo(NameKey project, List<URIish> candidates) {
          return candidates;
        }

        @Override
        public SetMultimap<URIish, String> refsToReplicateTo(
            NameKey project, Set<String> refNames, List<URIish> candidates, List<URIish> targets) {
          if (candidates.isEmpty() || refNames.contains(PushOne.ALL_REFS)) {
            // A full sync is not tied to a single ref and covers refs belonging to every shard, so
            // it goes to each URL; those pushes narrow down to their own refs via refsOwnedBy.
            return Instance.super.refsToReplicateTo(project, refNames, candidates, targets);
          }
          ImmutableList<URIish> sorted = sortedByUrl(candidates);
          SetMultimap<URIish, String> refsByUri = LinkedHashMultimap.create();
          for (String refName : refNames) {
            URIish owner = urlOwning(refName, sorted);
            if (targets.contains(owner)) {
              // Refs owned by a URL this push is not going to belong to that URL's own push.
              refsByUri.put(owner, refName);
            }
          }
          return refsByUri;
        }

        @Override
        public Set<String> refsOwnedBy(URIish uri, Set<String> refNames, List<URIish> candidates) {
          if (candidates.isEmpty()) {
            return refNames;
          }
          ImmutableList<URIish> sorted = sortedByUrl(candidates);
          ImmutableSet.Builder<String> owned = ImmutableSet.builder();
          for (String refName : refNames) {
            if (uri.equals(urlOwning(refName, sorted))) {
              owned.add(refName);
            }
          }
          return owned.build();
        }

        @Override
        public URIish failover(List<URIish> candidates, URIish failed) {
          if (candidates.size() < 2) {
            return failed;
          }
          return nextAfter(candidates, failed);
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
   * in which the URLs happen to be configured. Shared by the sharding strategies, which must agree
   * on the ordering for every host to derive the same mapping from the same config.
   */
  private static ImmutableList<URIish> sortedByUrl(List<URIish> candidates) {
    return ImmutableList.sortedCopyOf(Comparator.comparing(URIish::toString), candidates);
  }

  /**
   * Returns the URL owning the given ref, by hashing its name over the sorted candidates.
   *
   * <p>Asked again at push time, on a different host than the one that scheduled the push, so it
   * may depend on nothing but its arguments. Together with {@link String#hashCode()}, which is
   * specified, this makes every host reading the same config agree on where a ref belongs.
   */
  private static URIish urlOwning(String refName, ImmutableList<URIish> sortedCandidates) {
    return sortedCandidates.get(Math.floorMod(refName.hashCode(), sortedCandidates.size()));
  }

  /**
   * Returns the candidate following the failed one, wrapping around, or the failed one if absent.
   */
  private static URIish nextAfter(List<URIish> candidates, URIish failed) {
    ImmutableList<URIish> sorted = sortedByUrl(candidates);
    int failedIndex = sorted.indexOf(failed);
    if (failedIndex < 0) {
      return failed;
    }
    return sorted.get((failedIndex + 1) % sorted.size());
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

  /**
   * A stateful executor for a {@link UrlDistributionStrategy} strategy.
   *
   * <p>A strategy is asked per reason rather than once for every call site, so that what an
   * operation does when a remote has several URLs is decided here instead of by the callers, and
   * the state a strategy keeps between calls, such as the position in a rotation, lives here.
   */
  @FunctionalInterface
  public interface Instance {
    /**
     * Selects the URLs to replicate a project to out of the candidates, which is what pushing its
     * refs, creating it and updating its HEAD all follow.
     *
     * @param project project being replicated, used by project-affine strategies.
     * @param candidates URLs the project could be pushed to.
     * @return the subset of candidates to push to.
     */
    List<URIish> urlsToReplicateTo(Project.NameKey project, List<URIish> candidates);

    /**
     * Selects the URLs to create a project on out of the candidates.
     *
     * <p>Defaults to the URLs the project is replicated to, since its refs have to land somewhere
     * that holds it, and is separate from them so that a strategy can create a project on URLs it
     * would not push every ref to.
     *
     * @param project project being created.
     * @param candidates URLs the project could be created on.
     * @return the subset of candidates to create on.
     */
    default List<URIish> urlsToCreateOn(Project.NameKey project, List<URIish> candidates) {
      return urlsToReplicateTo(project, candidates);
    }

    /**
     * Selects the URLs to delete a project from out of the candidates.
     *
     * <p>Defaults to the URLs the project is replicated to, which is where it was written, and is
     * separate from them so that a strategy can delete from URLs it would not push to.
     *
     * @param project project being deleted.
     * @param candidates URLs the project could be deleted from.
     * @return the subset of candidates to delete from.
     */
    default List<URIish> urlsToDeleteFrom(Project.NameKey project, List<URIish> candidates) {
      return urlsToReplicateTo(project, candidates);
    }

    /**
     * Splits the refs updated by one event over the URLs to replicate them to.
     *
     * <p>Defaults to sending every ref to each URL the project is replicated to, which is what the
     * strategies placing whole events do; one placing individual refs overrides this to send each
     * ref to the URL holding it.
     *
     * @param project project being replicated.
     * @param refNames refs updated by the event, or {@code PushOne.ALL_REFS} for a full sync.
     * @param candidates every URL the project could be pushed to. Ref placement is computed over
     *     all of them, so that narrowing a push to some of the URLs sends the refs those URLs hold
     *     rather than re-hashing every ref onto them.
     * @param targets the candidates this push may go to, already narrowed by the caller's url
     *     match.
     * @return the refs to push, keyed by the URL to push them to.
     */
    default SetMultimap<URIish, String> refsToReplicateTo(
        Project.NameKey project,
        Set<String> refNames,
        List<URIish> candidates,
        List<URIish> targets) {
      return everyRefToEachUri(urlsToReplicateTo(project, targets), refNames);
    }

    /**
     * Narrows the refs a URL is about to push down to the ones it holds.
     *
     * <p>Asked at push time, on a different host than the one that scheduled the push, so that a
     * full sync, which is scheduled on every URL, still pushes each out-of-date ref exactly once.
     * Defaults to every ref, as the strategies placing whole events already pushed each event to
     * the URLs it belongs to; one placing individual refs must answer it the same way here and in
     * {@link #refsToReplicateTo}, depending on nothing but its arguments.
     *
     * @param uri URL about to be pushed to.
     * @param refNames refs the push would otherwise send.
     * @param candidates every URL the project could be pushed to.
     * @return the subset of refs this URL holds.
     */
    default Set<String> refsOwnedBy(URIish uri, Set<String> refNames, List<URIish> candidates) {
      return refNames;
    }

    /**
     * If a push to any URI returned by {@link #urlsToReplicateTo(Project.NameKey, List)} fails,
     * {@link #failover(List, URIish)}} is invoked to select the next URI for retry.
     */
    default URIish failover(List<URIish> candidates, URIish failed) {
      return failed;
    }
  }
}
