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

import com.google.common.base.Charsets;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.Url;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class GerritRestApi extends AbstractHttpApi {

  public interface Factory {
    GerritRestApi create(URIish uri);
  }

  @Inject
  GerritRestApi(
      CredentialsFactory credentials,
      CloseableHttpClient httpClient,
      RemoteConfig remoteConfig,
      @Assisted URIish uri) {
    super(credentials, httpClient, remoteConfig, uri);
  }

  @Override
  protected HttpUriRequest newCreateProjectRequest(Project.NameKey project, URIish uri) {
    return new HttpPut(
        String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get())));
  }

  @Override
  protected HttpUriRequest newDeleteProjectRequest(Project.NameKey project, URIish uri) {
    return new HttpDelete(
        String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get())));
  }

  @Override
  protected HttpUriRequest newUpdateHeadRequest(
      Project.NameKey project, String newHead, URIish uri) {
    String url = String.format("%s/a/projects/%s/HEAD", toHttpUri(uri), Url.encode(project.get()));
    HttpPut req = new HttpPut(url);
    req.setEntity(
        new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), Charsets.UTF_8.name()));
    req.addHeader(new BasicHeader("Content-Type", "application/json"));
    return req;
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
