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
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.time.Duration;
import org.eclipse.jgit.transport.URIish;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AutoRepairTrackerTest {
  private static final Project.NameKey PROJECT = Project.nameKey("foo");
  private static final String REMOTE = "mirror";

  @Mock private ReplicationConfig replicationConfig;

  private AutoCloseable mocks;
  private AutoRepairTracker tracker;
  private URIish uri1;
  private URIish uri2;

  @Before
  public void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    when(replicationConfig.getAutoRepairInterval()).thenReturn(Duration.ofDays(3));
    when(replicationConfig.getAutoRepairMaxAttempts()).thenReturn(5);

    tracker = new AutoRepairTracker(replicationConfig);
    uri1 = new URIish("ssh://mirror1.example.com/foo.git");
    uri2 = new URIish("ssh://mirror2.example.com/foo.git");
  }

  @After
  public void tearDown() throws Exception {
    mocks.close();
  }

  @Test
  public void allowsRepairUpToMaxAttemptsPerDestination() {
    when(replicationConfig.getAutoRepairInterval()).thenReturn(Duration.ZERO);
    for (int i = 0; i < 5; i++) {
      assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isTrue();
    }
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isFalse();
  }

  @Test
  public void tracksAttemptsSeparatelyPerDestination() {
    when(replicationConfig.getAutoRepairInterval()).thenReturn(Duration.ZERO);
    when(replicationConfig.getAutoRepairMaxAttempts()).thenReturn(1);

    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isTrue();
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isFalse();
    assertThat(tryRepair(uri2, UrlDistributionStrategy.ALL)).isTrue();
  }

  @Test
  public void roundRobinSharesAttemptsAcrossUrls() {
    when(replicationConfig.getAutoRepairInterval()).thenReturn(Duration.ZERO);
    when(replicationConfig.getAutoRepairMaxAttempts()).thenReturn(1);

    assertThat(tryRepair(uri1, UrlDistributionStrategy.ROUND_ROBIN)).isTrue();
    assertThat(tryRepair(uri2, UrlDistributionStrategy.ROUND_ROBIN)).isFalse();
  }

  @Test
  public void allowsRepairWithoutIntervalWhenIntervalIsZero() {
    when(replicationConfig.getAutoRepairInterval()).thenReturn(Duration.ZERO);
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isTrue();
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isTrue();
  }

  @Test
  public void disabledWhenMaxAttemptsIsZero() {
    when(replicationConfig.getAutoRepairMaxAttempts()).thenReturn(0);
    assertThat(tracker.isEnabled()).isFalse();
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isFalse();
  }

  @Test
  public void enforcesIntervalBetweenAttempts() {
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isTrue();
    assertThat(tryRepair(uri1, UrlDistributionStrategy.ALL)).isFalse();
  }

  @Test
  public void rejectsNonCopyableDestination() throws Exception {
    URIish httpUri = new URIish("http://mirror.example.com/foo.git");
    assertThat(tryRepair(httpUri, UrlDistributionStrategy.ALL)).isFalse();
  }

  private boolean tryRepair(URIish uri, UrlDistributionStrategy strategy) {
    return tracker.tryBeginRepair(PROJECT, uri, REMOTE, strategy);
  }
}
