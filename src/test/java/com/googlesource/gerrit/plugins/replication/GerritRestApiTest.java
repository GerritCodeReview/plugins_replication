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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.replication.HttpResponseHandler.HttpResult;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GerritRestApiTest {
  @Mock private CredentialsFactory credentialsFactory;
  @Mock private CredentialsProvider credentialsProvider;
  @Mock private CloseableHttpClient httpClient;
  @Mock private RemoteConfig remoteConfig;

  @Test
  public void shouldSendCompleteGitConfig() throws Exception {
    URIish uri = new URIish("https://example.com");
    String gitConfig =
        "[core]\n\trepositoryFormatVersion = 1\n"
            + "[extensions]\n\trefStorage = reftable\n"
            + "[custom]\n\tvalue = preserved\n";
    when(remoteConfig.getName()).thenReturn("test");
    when(credentialsFactory.create("test")).thenReturn(credentialsProvider);
    when(httpClient.execute(
            any(HttpPut.class), any(HttpResponseHandler.class), any(HttpClientContext.class)))
        .thenReturn(new HttpResult(true, ""));

    assertThat(
            new GerritRestApi(credentialsFactory, httpClient, remoteConfig, uri)
                .createProject(Project.nameKey("project"), "refs/heads/main", true, gitConfig))
        .isTrue();

    ArgumentCaptor<HttpPut> request = ArgumentCaptor.forClass(HttpPut.class);
    verify(httpClient)
        .execute(request.capture(), any(HttpResponseHandler.class), any(HttpClientContext.class));
    String requestBody = EntityUtils.toString(request.getValue().getEntity());
    assertThat(
            JsonParser.parseString(requestBody).getAsJsonObject().get("git_config").getAsString())
        .isEqualTo(gitConfig);
  }
}
