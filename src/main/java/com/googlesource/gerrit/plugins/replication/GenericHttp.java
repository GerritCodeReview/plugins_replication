// Copyright (C) 2020 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.entities.Project.NameKey;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class GenericHttp extends AbstractHttpApi {

  public interface Factory {
    GenericHttp create(URIish uri);
  }

  @Inject
  GenericHttp(
      CredentialsFactory credentials,
      CloseableHttpClient httpClient,
      RemoteConfig remoteConfig,
      @Assisted URIish uri) {
    super(credentials, httpClient, remoteConfig, uri);
  }

  @Override
  protected HttpUriRequest newCreateProjectRequest(NameKey project, URIish uri) {
    return new HttpPut(uri.toString());
  }

  @Override
  protected HttpUriRequest newDeleteProjectRequest(NameKey project, URIish uri) {
    return new HttpDelete(uri.toString());
  }

  @Override
  protected HttpUriRequest newUpdateHeadRequest(NameKey project, String newHead, URIish uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean updateHead(NameKey project, String newHead) {
    // TODO(sasa.zivkov)
    repLog.error("updateHead not supported over GeneticHttp API");
    return false;
  }
}
