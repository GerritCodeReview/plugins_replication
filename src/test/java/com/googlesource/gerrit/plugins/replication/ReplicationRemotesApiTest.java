// Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.io.MoreFiles;
import com.google.common.truth.StringSubject;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.replication.api.ConfigResource;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfigOverrides;
import com.googlesource.gerrit.plugins.replication.api.ReplicationRemotesApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReplicationRemotesApiTest {

  private Path testSite;
  private SecureStore secureStoreMock;
  private FileConfigResource baseConfig;
  private Injector testInjector;
  private AtomicReference<ReplicationConfigOverrides> testOverrides;

  @Before
  public void setUp() throws Exception {
    testSite = Files.createTempDirectory("replicationRemotesUpdateTest");
    secureStoreMock = mock(SecureStore.class);
    baseConfig = new FileConfigResource(new SitePaths(testSite));
    testOverrides = new AtomicReference(new TestReplicationConfigOverrides());
    testInjector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(ConfigResource.class).toInstance(baseConfig);
                bind(SecureStore.class).toInstance(secureStoreMock);
                bind(ReplicationRemotesApi.class).to(ReplicationRemotesApiImpl.class);
                DynamicItem.itemOf(binder(), ReplicationConfigOverrides.class);
                DynamicItem.bind(binder(), ReplicationConfigOverrides.class)
                    .toProvider(testOverrides::get);
              }
            });
  }

  @After
  public void tearDown() throws Exception {
    MoreFiles.deleteRecursively(testSite, ALLOW_INSECURE);
  }

  @Test
  public void shouldThrowWhenNoRemotesInTheUpdate() {
    Config update = new Config();
    ReplicationRemotesApi objectUnderTest = newReplicationConfigUpdater();

    assertThrows(IllegalArgumentException.class, () -> objectUnderTest.update(update));

    update.setString("non-remote", null, "value", "one");

    assertThrows(IllegalArgumentException.class, () -> objectUnderTest.update(update));
  }

  @Test
  public void addRemoteSectionToBaseConfigWhenNoOverrides() throws Exception {
    testOverrides.set(null);
    String url = "fake_url";
    Config update = new Config();
    setRemoteSite(update, "url", url);
    ReplicationRemotesApi objectUnderTest = newReplicationConfigUpdater();

    objectUnderTest.update(update);

    assertRemoteSite(baseConfig.getConfig(), "url").isEqualTo(url);
  }

  @Test
  public void addRemoteSectionToBaseOverridesConfig() throws Exception {
    String url = "fake_url";
    Config update = new Config();
    setRemoteSite(update, "url", url);
    ReplicationRemotesApi objectUnderTest = newReplicationConfigUpdater();

    objectUnderTest.update(update);

    assertRemoteSite(testOverrides.get().getConfig(), "url").isEqualTo(url);
    assertRemoteSite(baseConfig.getConfig(), "url").isNull();
  }

  @Test
  public void encryptPassword() throws Exception {
    Config update = new Config();
    String password = "my_secret_password";
    setRemoteSite(update, "password", password);
    ReplicationRemotesApi objectUnderTest = newReplicationConfigUpdater();

    objectUnderTest.update(update);

    verify(secureStoreMock).setList("remote", "site", "password", List.of(password));
    assertRemoteSite(baseConfig.getConfig(), "password").isNull();
    assertRemoteSite(testOverrides.get().getConfig(), "password").isNull();
  }

  private void setRemoteSite(Config config, String name, String value) {
    config.setString("remote", "site", name, value);
  }

  private StringSubject assertRemoteSite(Config config, String name) {
    return assertThat(config.getString("remote", "site", name));
  }

  private ReplicationRemotesApi newReplicationConfigUpdater() {
    return testInjector.getInstance(ReplicationRemotesApi.class);
  }

  static class TestReplicationConfigOverrides implements ReplicationConfigOverrides {
    private Config config = new Config();

    @Override
    public Config getConfig() {
      return config;
    }

    @Override
    public void update(Config update) throws IOException {
      config = update;
    }

    @Override
    public String getVersion() {
      return "none";
    }
  }
}
