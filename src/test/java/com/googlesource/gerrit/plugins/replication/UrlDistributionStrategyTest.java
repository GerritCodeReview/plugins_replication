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
}
