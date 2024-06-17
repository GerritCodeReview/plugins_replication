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
  private String url1;
  private String remoteName1;
  private String url2;
  private String remoteName2;

  @Before
  public void setUp() throws Exception {
    testSite = Files.createTempDirectory("replicationRemotesUpdateTest");
    secureStoreMock = mock(SecureStore.class);
    baseConfig = new FileConfigResource(new SitePaths(testSite));
    testOverrides = new AtomicReference<>(new TestReplicationConfigOverrides());
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
    url1 = "fake_url1";
    remoteName1 = "site1";
    url2 = "fake_url2";
    remoteName2 = "site2";
  }

  @After
  public void tearDown() throws Exception {
    MoreFiles.deleteRecursively(testSite, ALLOW_INSECURE);
  }

  @Test
  public void shouldThrowWhenNoRemotesInTheUpdate() {
    Config update = new Config();
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();

    assertThrows(IllegalArgumentException.class, () -> objectUnderTest.update(update));

    update.setString("non-remote", null, "value", "one");

    assertThrows(IllegalArgumentException.class, () -> objectUnderTest.update(update));
  }

  @Test
  public void shouldReturnEmptyConfigWhenNoRemotes() {
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();
    assertThat(objectUnderTest.get("site").getSections()).isEmpty();
  }

  @Test
  public void addRemoteSectionsToBaseConfigWhenNoOverrides() throws Exception {
    testOverrides.set(null);

    Config update = new Config();
    setRemoteSite(update, remoteName1, "url", url1);
    setRemoteSite(update, remoteName2, "url", url2);
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();

    objectUnderTest.update(update);

    assertRemoteSite(baseConfig.getConfig(), remoteName1, "url").isEqualTo(url1);
    assertRemoteSite(baseConfig.getConfig(), remoteName2, "url").isEqualTo(url2);
  }

  @Test
  public void shouldReturnRemotesFromBaseConfigWhenNoOverrides() {
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();

    baseConfig.getConfig().setString("remote", remoteName1, "url", url1);
    baseConfig.getConfig().setString("remote", remoteName2, "url", url2);

    Config remotesConfig = objectUnderTest.get(remoteName1, remoteName2);
    assertRemoteSite(remotesConfig, remoteName1, "url").isEqualTo(url1);
    assertRemoteSite(remotesConfig, remoteName2, "url").isEqualTo(url2);
  }

  @Test
  public void addRemotesSectionToBaseOverridesConfig() throws Exception {
    Config update = new Config();
    setRemoteSite(update, remoteName1, "url", url1);
    setRemoteSite(update, remoteName2, "url", url2);
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();

    objectUnderTest.update(update);

    assertRemoteSite(testOverrides.get().getConfig(), remoteName1, "url").isEqualTo(url1);
    assertRemoteSite(testOverrides.get().getConfig(), remoteName2, "url").isEqualTo(url2);
    assertRemoteSite(baseConfig.getConfig(), remoteName1, "url").isNull();
    assertRemoteSite(baseConfig.getConfig(), remoteName2, "url").isNull();

    Config remotesConfig = objectUnderTest.get(remoteName1, remoteName2);
    assertRemoteSite(remotesConfig, remoteName1, "url").isEqualTo(url1);
    assertRemoteSite(remotesConfig, remoteName2, "url").isEqualTo(url2);
  }

  @Test
  public void shouldEncryptPasswordButNotStoreInConfig() throws Exception {
    Config update = new Config();
    String password = "my_secret_password";
    String remoteName = "site";
    setRemoteSite(update, remoteName, "password", password);
    ReplicationRemotesApi objectUnderTest = getReplicationRemotesApi();

    objectUnderTest.update(update);

    verify(secureStoreMock).setList("remote", remoteName, "password", List.of(password));
    assertRemoteSite(baseConfig.getConfig(), remoteName, "password").isNull();
    assertRemoteSite(testOverrides.get().getConfig(), remoteName, "password").isNull();
    assertRemoteSite(objectUnderTest.get(remoteName), remoteName, "password").isNull();
  }

  private void setRemoteSite(Config config, String remoteName, String name, String value) {
    config.setString("remote", remoteName, name, value);
  }

  private StringSubject assertRemoteSite(Config config, String remoteName, String name) {
    return assertThat(config.getString("remote", remoteName, name));
  }

  private ReplicationRemotesApi getReplicationRemotesApi() {
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
