// Copyright (C) 2018 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.GerritSshApi.GERRIT_ADMIN_PROTOCOL_PREFIX;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.common.base.Charsets;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.reviewdb.client.Project;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class GerritRestApi implements AdminApi {

  public interface Factory {
    GerritRestApi create(URIish uri);
  }

  private final CredentialsFactory credentials;
  private final CloseableHttpClient httpClient;
  private final RemoteConfig remoteConfig;
  private final URIish uri;

  @Inject
  GerritRestApi(
      CredentialsFactory credentials,
      CloseableHttpClient httpClient,
      RemoteConfig remoteConfig,
      @Assisted URIish uri) {
    this.credentials = credentials;
    this.httpClient = httpClient;
    this.remoteConfig = remoteConfig;
    this.uri = uri;
  }

  @Override
  public boolean createProject(Project.NameKey project, String head) {
    repLog.info("Creating project {} on {}", project, uri);
    String url = String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get()));
    try {
      return httpClient
          .execute(new HttpPut(url), new HttpResponseHandler(), getContext())
          .isSuccessful();
    } catch (IOException e) {
      repLog.error("Couldn't perform project creation on {}", uri, e);
      return false;
    }
  }

  @Override
  public void deleteProject(Project.NameKey project) {
    repLog.info("Deleting project {} on {}", project, uri);
    String url = String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get()));
    try {
      httpClient.execute(new HttpDelete(url), new HttpResponseHandler(), getContext());
    } catch (IOException e) {
      repLog.error("Couldn't perform project deletion on {}", uri, e);
    }
  }

  @Override
  public void updateHead(Project.NameKey project, String newHead) {
    repLog.info("Updating head of {} on {}", project, uri);
    String url = String.format("%s/a/projects/%s/HEAD", toHttpUri(uri), Url.encode(project.get()));
    try {
      HttpPut req = new HttpPut(url);
      req.setEntity(
          new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), Charsets.UTF_8.name()));
      req.addHeader(new BasicHeader("Content-Type", "application/json"));
      httpClient.execute(req, new HttpResponseHandler(), getContext());
    } catch (IOException e) {
      repLog.error("Couldn't perform update head on {}", uri, e);
    }
  }

  private HttpClientContext getContext() {
    HttpClientContext ctx = HttpClientContext.create();
    CredentialsProvider cp = new BasicCredentialsProvider();
    SecureCredentialsProvider scp = credentials.create(remoteConfig.getName());
    cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(scp.getUser(), scp.getPass()));
    ctx.setCredentialsProvider(cp);
    return ctx;
  }

  private static String toHttpUri(URIish uri) {
    String u = uri.toString();
    if (u.startsWith(GERRIT_ADMIN_PROTOCOL_PREFIX)) {
      u = u.substring(GERRIT_ADMIN_PROTOCOL_PREFIX.length());
    }
    if (u.endsWith("/")) {
      return u;
    }
    return u + "/";
  }
}
