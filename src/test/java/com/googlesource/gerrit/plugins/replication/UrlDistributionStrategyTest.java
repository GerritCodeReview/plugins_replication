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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.gerrit.entities.Project;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

public class UrlDistributionStrategyTest {
  private static final Project.NameKey PROJECT = Project.nameKey("some/project");
  private static final Project.NameKey OTHER_PROJECT = Project.nameKey("some/other/project");
  private static final String REF = "refs/heads/branch1";
  private static final Set<String> REFS =
      Set.of(
          REF,
          "refs/heads/branch2",
          "refs/heads/branch3",
          "refs/heads/branch4",
          "refs/heads/branch5",
          "refs/heads/branch6");

  private static ImmutableList<URIish> uris(String... hosts) throws URISyntaxException {
    ImmutableList.Builder<URIish> uris = ImmutableList.builder();
    for (String host : hosts) {
      uris.add(new URIish("git://" + host + "/${name}.git"));
    }
    return uris.build();
  }

  @Test
  public void shouldResolveConfigKeys() {
    assertThat(UrlDistributionStrategy.fromConfig("all")).isEqualTo(UrlDistributionStrategy.ALL);
    assertThat(UrlDistributionStrategy.fromConfig("roundRobin"))
        .isEqualTo(UrlDistributionStrategy.ROUND_ROBIN);
    assertThat(UrlDistributionStrategy.fromConfig("projectSharded"))
        .isEqualTo(UrlDistributionStrategy.PROJECT_SHARDED);
    assertThat(UrlDistributionStrategy.fromConfig("refSharded"))
        .isEqualTo(UrlDistributionStrategy.REF_SHARDED);
  }

  @Test
  public void shouldFallBackToAllForUnknownConfigKey() {
    assertThat(UrlDistributionStrategy.fromConfig("bogus")).isEqualTo(UrlDistributionStrategy.ALL);
    assertThat(UrlDistributionStrategy.fromConfig(null)).isEqualTo(UrlDistributionStrategy.ALL);
  }

  @Test
  public void allShouldSelectEveryCandidate() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor = UrlDistributionStrategy.ALL.newInstance();

    assertThat(distributor.select(PROJECT, candidates)).isEqualTo(candidates);
  }

  @Test
  public void roundRobinShouldRotateOnConsecutiveSelections() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.ROUND_ROBIN.newInstance();

    assertThat(distributor.select(PROJECT, candidates)).containsExactly(candidates.get(0));
    assertThat(distributor.select(PROJECT, candidates)).containsExactly(candidates.get(1));
    assertThat(distributor.select(PROJECT, candidates)).containsExactly(candidates.get(0));
  }

  @Test
  public void roundRobinShouldRotateRegardlessOfProject() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.ROUND_ROBIN.newInstance();

    assertThat(distributor.select(PROJECT, candidates)).containsExactly(candidates.get(0));
    assertThat(distributor.select(OTHER_PROJECT, candidates)).containsExactly(candidates.get(1));
  }

  @Test
  public void projectShardedShouldSelectExactlyOneCandidate() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance();

    List<URIish> selected = distributor.select(PROJECT, candidates);

    assertThat(selected).hasSize(1);
    assertThat(candidates).containsAtLeastElementsIn(selected);
  }

  @Test
  public void projectShardedShouldPinProjectToSameCandidateAcrossSelections() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance();

    List<URIish> first = distributor.select(PROJECT, candidates);
    for (int i = 0; i < 10; i++) {
      assertThat(distributor.select(PROJECT, candidates)).isEqualTo(first);
    }
  }

  @Test
  public void projectShardedShouldAgreeAcrossInstances() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");

    List<URIish> first =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance().select(PROJECT, candidates);
    List<URIish> second =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance().select(PROJECT, candidates);

    assertThat(second).isEqualTo(first);
  }

  @Test
  public void projectShardedShouldIgnoreCandidateOrdering() throws Exception {
    ImmutableList<URIish> candidates =
        uris("replica1", "replica2", "replica3", "replica4", "replica5");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance();

    List<URIish> expected = distributor.select(PROJECT, candidates);

    List<URIish> shuffled = new ArrayList<>(candidates);
    Random random = new Random(42);
    for (int i = 0; i < 20; i++) {
      Collections.shuffle(shuffled, random);
      assertThat(distributor.select(PROJECT, shuffled)).isEqualTo(expected);
    }
  }

  @Test
  public void projectShardedShouldSpreadProjectsOverAllCandidates() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance();

    Set<URIish> selected = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      selected.addAll(distributor.select(Project.nameKey("project" + i), candidates));
    }

    assertThat(selected).containsExactlyElementsIn(candidates);
  }

  @Test
  public void projectShardedShouldSelectOnlyCandidateWhenSingleUrlConfigured() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.PROJECT_SHARDED.newInstance();

    assertThat(distributor.select(PROJECT, candidates)).isEqualTo(candidates);
    assertThat(distributor.select(OTHER_PROJECT, candidates)).isEqualTo(candidates);
  }

  @Test
  public void shouldSelectNothingWhenThereAreNoCandidates() {
    for (UrlDistributionStrategy strategy : UrlDistributionStrategy.values()) {
      assertThat(strategy.newInstance().select(PROJECT, List.of())).isEmpty();
    }
  }

  @Test
  public void shouldDistributeNothingWhenThereAreNoCandidates() {
    for (UrlDistributionStrategy strategy : UrlDistributionStrategy.values()) {
      assertThat(strategy.newInstance().distribute(PROJECT, REFS, List.of())).isEmpty();
    }
  }

  @Test
  public void projectAffineStrategiesShouldSendEveryRefToEachSelectedUrl() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");

    for (UrlDistributionStrategy strategy :
        List.of(
            UrlDistributionStrategy.ALL,
            UrlDistributionStrategy.ROUND_ROBIN,
            UrlDistributionStrategy.PROJECT_SHARDED)) {
      UrlDistributionStrategy.Instance distributor = strategy.newInstance();

      SetMultimap<URIish, String> refsByUri = distributor.distribute(PROJECT, REFS, candidates);

      for (URIish uri : refsByUri.keySet()) {
        assertThat(refsByUri).valuesForKey(uri).containsExactlyElementsIn(REFS);
      }
    }
  }

  @Test
  public void refShardedShouldSendEachRefToExactlyOneUrl() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    SetMultimap<URIish, String> refsByUri = distributor.distribute(PROJECT, REFS, candidates);

    assertThat(candidates).containsAtLeastElementsIn(refsByUri.keySet());
    assertThat(refsByUri.values()).containsExactlyElementsIn(REFS);

    ImmutableList<URIish> onlyCandidate = uris("replica1");
    assertThat(distributor.distribute(PROJECT, REFS, onlyCandidate))
        .valuesForKey(onlyCandidate.get(0))
        .containsExactlyElementsIn(REFS);
  }

  @Test
  public void refShardedShouldMapEachRefToTheSameUrlEverywhere() throws Exception {
    ImmutableList<URIish> candidates =
        uris("replica1", "replica2", "replica3", "replica4", "replica5");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    SetMultimap<URIish, String> expected = distributor.distribute(PROJECT, REFS, candidates);

    // The strategy holds no state, so replaying the event does not rotate the refs.
    assertThat(distributor.distribute(PROJECT, REFS, candidates)).isEqualTo(expected);

    // Every host agrees, no matter which order it happens to list the URLs in.
    List<URIish> shuffled = new ArrayList<>(candidates);
    Random random = new Random(42);
    for (int i = 0; i < 20; i++) {
      Collections.shuffle(shuffled, random);
      assertThat(
              UrlDistributionStrategy.REF_SHARDED.newInstance().distribute(PROJECT, REFS, shuffled))
          .isEqualTo(expected);
    }

    // Only the ref name decides, neither the project nor the other refs of the event.
    assertThat(distributor.distribute(OTHER_PROJECT, REFS, candidates)).isEqualTo(expected);
    URIish uriWhenAlone =
        Iterables.getOnlyElement(distributor.distribute(PROJECT, Set.of(REF), candidates).keySet());
    assertThat(expected).valuesForKey(uriWhenAlone).contains(REF);
  }

  @Test
  public void refShardedShouldSpreadRefsOverAllCandidates() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    Set<String> manyRefs = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      manyRefs.add("refs/heads/branch" + i);
    }

    SetMultimap<URIish, String> refsByUri = distributor.distribute(PROJECT, manyRefs, candidates);

    assertThat(refsByUri.keySet()).containsExactlyElementsIn(candidates);
  }

  @Test
  public void refsOwnedByShouldMatchTheDistributionAndDefaultToEveryRef() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");

    // refSharded owns exactly the refs it would have distributed to that URL, so a full sync
    // scheduled on every URL still pushes each out-of-date ref exactly once.
    UrlDistributionStrategy.Instance refSharded = UrlDistributionStrategy.REF_SHARDED.newInstance();
    SetMultimap<URIish, String> distributed = refSharded.distribute(PROJECT, REFS, candidates);
    for (URIish uri : candidates) {
      assertThat(refSharded.refsOwnedBy(uri, REFS, candidates))
          .containsExactlyElementsIn(distributed.get(uri));
    }

    // The strategies that do not shard per ref leave a scheduled push free to push every ref.
    for (UrlDistributionStrategy strategy :
        List.of(
            UrlDistributionStrategy.ALL,
            UrlDistributionStrategy.ROUND_ROBIN,
            UrlDistributionStrategy.PROJECT_SHARDED)) {
      assertThat(strategy.newInstance().refsOwnedBy(candidates.get(0), REFS, candidates))
          .containsExactlyElementsIn(REFS);
    }
  }

  @Test
  public void refShardedShouldSendFullSyncToAllCandidates() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    Set<String> allRefs = Set.of(PushOne.ALL_REFS);
    SetMultimap<URIish, String> refsByUri = distributor.distribute(PROJECT, allRefs, candidates);

    assertThat(refsByUri.keySet()).containsExactlyElementsIn(candidates);
    for (URIish uri : candidates) {
      assertThat(refsByUri).valuesForKey(uri).containsExactly(PushOne.ALL_REFS);
    }
  }

  @Test
  public void refShardedShouldSelectEveryCandidateWhenNoRefIsKnown() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    assertThat(distributor.select(PROJECT, candidates)).isEqualTo(candidates);
  }

  @Test
  public void refShardedShouldFailoverToAnotherCandidate() throws Exception {
    ImmutableList<URIish> candidates = uris("replica1", "replica2", "replica3");
    UrlDistributionStrategy.Instance distributor =
        UrlDistributionStrategy.REF_SHARDED.newInstance();

    for (URIish failed : candidates) {
      assertThat(distributor.failover(candidates, failed)).isNotEqualTo(failed);
    }

    ImmutableList<URIish> onlyCandidate = uris("replica1");
    assertThat(distributor.failover(onlyCandidate, onlyCandidate.get(0)))
        .isEqualTo(onlyCandidate.get(0));
  }
}
