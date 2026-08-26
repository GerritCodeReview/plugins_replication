// Copyright (C) 2021 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DestinationConfigurationTest {
  private static final String REMOTE = "foo";

  @Mock private RemoteConfig remoteConfigMock;
  @Mock private Config cfgMock;

  private DestinationConfiguration objectUnderTest;

  @Before
  public void setUp() {
    when(remoteConfigMock.getName()).thenReturn(REMOTE);
    when(cfgMock.getStringList(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(new String[] {});
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);
  }

  @Test
  public void shouldIgnoreRemotePushBatchSizeWhenClusterReplicationIsConfigured() {
    // given
    when(cfgMock.getInt("remote", REMOTE, "pushBatchSize", 0)).thenReturn(1);
    when(cfgMock.getInt("replication", "distributionInterval", 0)).thenReturn(1);

    // when
    int actual = objectUnderTest.getPushBatchSize();

    // then
    assertThat(actual).isEqualTo(0);
  }

  @Test
  public void shouldIgnoreGlobalPushBatchSizeWhenClusterReplicationIsConfigured() {
    // given
    int globalPushBatchSize = 1;
    when(cfgMock.getInt("gerrit", "pushBatchSize", 0)).thenReturn(globalPushBatchSize);
    when(cfgMock.getInt("remote", REMOTE, "pushBatchSize", globalPushBatchSize))
        .thenReturn(globalPushBatchSize);
    when(cfgMock.getInt("replication", "distributionInterval", 0)).thenReturn(1);

    // when
    int actual = objectUnderTest.getPushBatchSize();

    // then
    assertThat(actual).isEqualTo(0);
  }

  @Test
  public void shouldReturnRemotePushBatchSizeWhenClusterReplicationIsNotConfigured() {
    // given
    when(cfgMock.getInt("remote", REMOTE, "pushBatchSize", 0)).thenReturn(1);

    // when
    int actual = objectUnderTest.getPushBatchSize();

    // then
    assertThat(actual).isEqualTo(1);
  }

  @Test
  public void shouldReturnGlobalPushBatchSizeWhenClusterReplicationIsNotConfigured() {
    // given
    int globalPushBatchSize = 1;
    when(cfgMock.getInt("gerrit", "pushBatchSize", 0)).thenReturn(globalPushBatchSize);
    when(cfgMock.getInt("remote", REMOTE, "pushBatchSize", globalPushBatchSize))
        .thenReturn(globalPushBatchSize);

    // when
    int actual = objectUnderTest.getPushBatchSize();

    // then
    assertThat(actual).isEqualTo(globalPushBatchSize);
  }

  @Test
  public void shouldDefaultReplicationRetryToOneMinute() {
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(60);
  }

  @Test
  public void shouldTreatBareReplicationRetryAsMinutes() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("2");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(120);
  }

  @Test
  public void shouldParseReplicationRetryWithSecondsSuffix() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("30 s");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(30);
  }

  @Test
  public void shouldParseReplicationRetryWithMinutesSuffix() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("2 m");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(120);
  }

  @Test
  public void shouldTreatZeroReplicationRetryAsNoDelay() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("0");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(0);
  }

  @Test
  public void shouldClampNegativeBareReplicationRetryToZero() {
    // given: a bare negative was accepted historically (cfg.getInt) and clamped to zero
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("-1");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(0);
  }

  @Test
  public void shouldDefaultReplicationRetryWhenEmpty() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("  ");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(60);
  }

  @Test
  public void shouldClampHugeReplicationRetryToIntMax() {
    // given: a value whose seconds exceed Integer.MAX_VALUE must not overflow to a negative int
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("999999999999 s");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getRetryDelay()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  public void shouldRejectInvalidReplicationRetry() {
    // given
    when(cfgMock.getString("remote", REMOTE, "replicationRetry")).thenReturn("banana");

    // when
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new DestinationConfiguration(remoteConfigMock, cfgMock));

    // then: the error names the offending config key
    assertThat(thrown).hasMessageThat().contains("remote." + REMOTE + ".replicationRetry");
  }

  @Test
  public void shouldDefaultUrlDistributionToAll() {
    assertThat(objectUnderTest.getUrlDistributionStrategy()).isEqualTo(UrlDistributionStrategy.ALL);
  }

  @Test
  public void shouldSetUrlDistributionToRoundRobinWhenConfigured() {
    // given
    when(cfgMock.getString("remote", REMOTE, "urlDistributionStrategy")).thenReturn("roundRobin");
    objectUnderTest = new DestinationConfiguration(remoteConfigMock, cfgMock);

    // when / then
    assertThat(objectUnderTest.getUrlDistributionStrategy())
        .isEqualTo(UrlDistributionStrategy.ROUND_ROBIN);
  }
}
