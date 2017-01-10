// Copyright (C) 2011 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.replication.Destination.encode;
import static com.googlesource.gerrit.plugins.replication.Destination.needsUrlEncoding;

import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

public class PushReplicationTest {

  @Test
  public void testNeedsUrlEncoding() throws URISyntaxException {
    assertThat(needsUrlEncoding(new URIish("http://host/path"))).isTrue();
    assertThat(needsUrlEncoding(new URIish("https://host/path"))).isTrue();
    assertThat(needsUrlEncoding(new URIish("amazon-s3://config/bucket/path"))).isTrue();

    assertThat(needsUrlEncoding(new URIish("host:path"))).isFalse();
    assertThat(needsUrlEncoding(new URIish("user@host:path"))).isFalse();
    assertThat(needsUrlEncoding(new URIish("git://host/path"))).isFalse();
    assertThat(needsUrlEncoding(new URIish("ssh://host/path"))).isFalse();
  }

  @Test
  public void urlEncoding() {
    assertThat(encode("foo/bar/thing")).isEqualTo("foo/bar/thing");
    assertThat(encode("-- All Projects --")).isEqualTo("--%20All%20Projects%20--");
    assertThat(encode("name/with a space")).isEqualTo("name/with%20a%20space");
    assertThat(encode("name\nwith-LF")).isEqualTo("name%0Awith-LF");
  }
}
