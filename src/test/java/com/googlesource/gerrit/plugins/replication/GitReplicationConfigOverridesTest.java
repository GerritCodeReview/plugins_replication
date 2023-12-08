// Copyright (C) 2023 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;
import static com.googlesource.gerrit.plugins.replication.GitReplicationConfigOverrides.REF_NAME;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.util.Providers;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitReplicationConfigOverridesTest {
  private static final AllProjectsName ALL_PROJECT = new AllProjectsName("All-Projects");

  @Mock GitRepositoryManager repoManager;

  private TestRepository<InMemoryRepository> repo;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(
        new DfsRepositoryDescription("test")));

    when(repoManager.openRepository(ALL_PROJECT)).thenReturn(repo.getRepository());
  }

  @After
  public void tearDown() throws Exception {
    repo.close();
  }

  @Test
  public void ignoreWhenTheresNoBranch() {
    GitReplicationConfigOverrides overrides = newGitReplicationConfigOverrides();

    String actualVersion = overrides.getVersion();
    Config actualConfig = overrides.getConfig();

    assertThat(actualVersion).isEmpty();
    assertThat(actualConfig.toText()).isEqualTo("");
  }

  @Test
  public void readMainConfigFile() throws Exception {
    createReplicationConfig(
        "[replication]\n\tmaxRetires = 30\n[remote \"internal\"]\n\tmirror = true");

    GitReplicationConfigOverrides overrides = newGitReplicationConfigOverrides();

    String actualVersion = overrides.getVersion();
    Config actualConfig = overrides.getConfig();

    assertThat(actualVersion).isNotEmpty();
    assertThat(actualConfig.getBoolean("remote", "internal", "mirror", false)).isTrue();
    assertThat(actualConfig.getInt("replication", "maxRetires", -1)).isEqualTo(30);
  }

  @Test
  public void readFanoutRemotes() throws Exception {
    createFanoutRemoteConfig("first", "[remote]\n\tmaxRetires = 30");
    createFanoutRemoteConfig("second", "[remote]\n\tmirror = true");

    GitReplicationConfigOverrides overrides = newGitReplicationConfigOverrides();

    String actualVersion = overrides.getVersion();
    Config actualConfig = overrides.getConfig();

    assertThat(actualVersion).isNotEmpty();
    assertThat(actualConfig.getInt("remote", "first", "maxRetires", -1)).isEqualTo(30);
    assertThat(actualConfig.getBoolean("remote", "second", "mirror", false)).isTrue();
  }

  @Test
  public void mergeConfigs() throws Exception {
    createReplicationConfig("[replication]\n\tmaxRetires = 30");
    createFanoutRemoteConfig("test", "[remote]\n\tmirror = true");

    GitReplicationConfigOverrides overrides = newGitReplicationConfigOverrides();

    String actualVersion = overrides.getVersion();
    Config actualConfig = overrides.getConfig();

    assertThat(actualVersion).isNotEmpty();
    assertThat(actualConfig.getInt("replication", "maxRetires", -1)).isEqualTo(30);
    assertThat(actualConfig.getBoolean("remote", "test", "mirror", false)).isTrue();
  }

  @Test
  public void removeRemoteSectionFromMainConfigWhenFanoutConfigIsPresent() throws Exception {
    createReplicationConfig("[replication]\n\tmaxRetires = 30\n[remote \"main\"]\n\tthreads = 1");
    createFanoutRemoteConfig("test", "[remote]\n\tmirror = true");

    Config actual = newGitReplicationConfigOverrides().getConfig();

    assertThat(actual.getNames("remote", "main")).isEmpty();
  }

  @Test
  public void ignoreNonRemoteSectionsInFanoutConfig() throws Exception {
    createFanoutRemoteConfig(
        "non-remote", "[remote]\n\tmirror = true\n[replication]\n\tmaxRetries = 1");

    Config actual = newGitReplicationConfigOverrides().getConfig();

    assertThat(actual.getNames("replication")).isEmpty();
  }

  private GitReplicationConfigOverrides newGitReplicationConfigOverrides() {
    return new GitReplicationConfigOverrides(repoManager, Providers.of(ALL_PROJECT));
  }

  private void createReplicationConfig(String content) throws Exception {
    repo.branch(REF_NAME).commit().add(CONFIG_NAME, content).create();
  }

  private void createFanoutRemoteConfig(String name, String content) throws Exception {
    repo
        .branch(REF_NAME)
        .commit()
        .add(CONFIG_DIR + name + ".config", content)
        .create();
  }
}
