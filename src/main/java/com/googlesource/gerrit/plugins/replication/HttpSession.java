// Copyright (C) 2015 The Android Open Source Project
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

import com.google.common.base.CharMatcher;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HttpSession {

  protected final String url;
  private final String user;
  private final String pass;
  private CloseableHttpClient client;

  public HttpSession(String url, String user, String pass) {
    this.url = CharMatcher.is('/').trimTrailingFrom(url);
    this.user = user;
    this.pass = pass;
  }

  /*
  public HttpResponse get(String path) throws IOException {
    HttpGet get = new HttpGet(url + path);
    return new HttpResponse(getClient().execute(get));
  }
  */

  protected CloseableHttpClient getClient() throws IOException {
    if (client == null) {
      URI uri = URI.create(url);
      BasicCredentialsProvider creds = new BasicCredentialsProvider();
      creds.setCredentials(new AuthScope(uri.getHost(), uri.getPort()),
          new UsernamePasswordCredentials(user, pass));

      SSLContext context;
      try {
        final TrustManager[] trustAllCerts =
            new TrustManager[] {new DummyX509TrustManager()};
        context = SSLContext.getInstance("TLS");
        context.init(null, trustAllCerts, null);
      } catch (KeyManagementException | NoSuchAlgorithmException e) {
        throw new IOException(e.getMessage());
      }

      SSLConnectionSocketFactory sf;
      sf = new SSLConnectionSocketFactory(context, new DummyHostnameVerifier());
      client = HttpClients
          .custom()
          .setSSLSocketFactory(sf)
          .setDefaultCredentialsProvider(creds)
          .setMaxConnPerRoute(10)
          .setMaxConnTotal(1024)
          .build();

    }
    return client;
  }

  private static class DummyX509TrustManager implements X509TrustManager {
    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return null;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType) {
      // no check
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType) {
      // no check
    }
  }

  private static class DummyHostnameVerifier implements HostnameVerifier {
    @Override
    public boolean verify(String hostname, SSLSession session) {
      // always accept
      return true;
    }
  }
}
