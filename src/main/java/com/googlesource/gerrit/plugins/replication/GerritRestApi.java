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
import org.eclipse.jgit.transport.CredentialItem;
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
    repLog.atInfo().log("Creating project %s on %s", project, uri);
    String url = String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get()));
    try {
      return httpClient
          .execute(new HttpPut(url), new HttpResponseHandler(), getContext())
          .isSuccessful();
    } catch (IOException e) {
      repLog.atSevere().log("Couldn't perform project creation on %s", uri, e);
      return false;
    }
  }

  @Override
  public boolean deleteProject(Project.NameKey project) {
    repLog.atInfo().log("Deleting project %s on %s", project, uri);
    String url = String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get()));
    try {
      httpClient.execute(new HttpDelete(url), new HttpResponseHandler(), getContext());
      return true;
    } catch (IOException e) {
      repLog.atSevere().log("Couldn't perform project deletion on %s", uri, e);
    }
    return false;
  }

  @Override
  public boolean updateHead(Project.NameKey project, String newHead) {
    repLog.atInfo().log("Updating head of %s on %s", project, uri);
    String url = String.format("%s/a/projects/%s/HEAD", toHttpUri(uri), Url.encode(project.get()));
    try {
      HttpPut req = new HttpPut(url);
      req.setEntity(
          new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), Charsets.UTF_8.name()));
      req.addHeader(new BasicHeader("Content-Type", "application/json"));
      httpClient.execute(req, new HttpResponseHandler(), getContext());
      return true;
    } catch (IOException e) {
      repLog.atSevere().log("Couldn't perform update head on %s", uri, e);
    }
    return false;
  }

  private HttpClientContext getContext() {
    HttpClientContext ctx = HttpClientContext.create();
    ctx.setCredentialsProvider(adapt(credentials.create(remoteConfig.getName())));
    return ctx;
  }

  private CredentialsProvider adapt(org.eclipse.jgit.transport.CredentialsProvider cp) {
    CredentialItem.Username user = new CredentialItem.Username();
    CredentialItem.Password pass = new CredentialItem.Password();
    if (cp.supports(user, pass) && cp.get(uri, user, pass)) {
      CredentialsProvider adapted = new BasicCredentialsProvider();
      adapted.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(user.getValue(), new String(pass.getValue())));
      return adapted;
    }
    return null;
  }

  private static String toHttpUri(URIish uri) {
    String u = uri.toString();
    if (u.startsWith(GERRIT_ADMIN_PROTOCOL_PREFIX)) {
      u = u.substring(GERRIT_ADMIN_PROTOCOL_PREFIX.length());
    }
    if (u.endsWith("/")) {
      return u.substring(0, u.length() - 1);
    }
    return u;
  }
}
