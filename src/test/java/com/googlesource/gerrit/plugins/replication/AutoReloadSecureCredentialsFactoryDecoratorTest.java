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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.securestore.SecureStore;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AutoReloadSecureCredentialsFactoryDecoratorTest extends AbstractConfigTest {
  private static final String REMOTE_TEST = "remote-test";
  private static final String USERNAME_CLEARTEXT = "username-clear";
  private static final String PASSWORD_CLEARTEXT = "password-clear";
  private static final String USERNAME_CIPHERTEXT = "username-encrypted";
  private static final String PASSWORD_CIPHERTEXT = "password-encrypted";

  @Mock private SecureStore secureStoreMock;
  @Mock private ReplicationConfig replicationConfigMock;

  public AutoReloadSecureCredentialsFactoryDecoratorTest() throws IOException {
    super();
  }

  @Override
  @Before
  public void setup() throws IOException {
    when(replicationConfigMock.getConfig()).thenReturn(new Config());

    when(secureStoreMock.get("remote", REMOTE_TEST, "username")).thenReturn(USERNAME_CIPHERTEXT);
    when(secureStoreMock.get("remote", REMOTE_TEST, "password")).thenReturn(PASSWORD_CIPHERTEXT);

    FileBasedConfig secureConfig = newSecureConfig();
    secureConfig.setString("remote", REMOTE_TEST, "username", USERNAME_CLEARTEXT);
    secureConfig.setString("remote", REMOTE_TEST, "password", PASSWORD_CLEARTEXT);
    secureConfig.save();
  }

  @Test
  public void shouldCreateLegacyCredentials() throws Exception {
    when(replicationConfigMock.useLegacyCredentials()).thenReturn(true);
    assertUsernamePasswordCredentials(
        getCredentialsProvider(), USERNAME_CLEARTEXT, PASSWORD_CLEARTEXT);
  }

  @Test
  public void shouldCreateEncryptedCredentialsByDefault() throws Exception {
    assertUsernamePasswordCredentials(
        getCredentialsProvider(), USERNAME_CIPHERTEXT, PASSWORD_CIPHERTEXT);
  }

  private UsernamePasswordCredentialsProvider getCredentialsProvider()
      throws ConfigInvalidException, IOException {
    AutoReloadSecureCredentialsFactoryDecorator credFactory =
        new AutoReloadSecureCredentialsFactoryDecorator(
            sitePaths, secureStoreMock, replicationConfigMock);
    CredentialsProvider legacyCredentials = credFactory.create(REMOTE_TEST);
    assertThat(legacyCredentials).isInstanceOf(UsernamePasswordCredentialsProvider.class);
    return (UsernamePasswordCredentialsProvider) legacyCredentials;
  }

  private static void assertUsernamePasswordCredentials(
      UsernamePasswordCredentialsProvider credentials, String username, String password) {
    CredentialItem.Username usernameItem = new CredentialItem.Username();
    CredentialItem.Password passwordItem = new CredentialItem.Password();
    assertThat(credentials.get(new URIish(), usernameItem, passwordItem)).isTrue();

    assertThat(usernameItem.getValue()).isEqualTo(username);
    assertThat(new String(passwordItem.getValue())).isEqualTo(password);
  }
}
