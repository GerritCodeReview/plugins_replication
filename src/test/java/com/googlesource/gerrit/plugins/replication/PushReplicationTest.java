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
import static com.googlesource.gerrit.plugins.replication.Destination.escape;
import static com.googlesource.gerrit.plugins.replication.Destination.needsUrlEscaping;

import com.google.gerrit.entities.Project;
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

public class PushReplicationTest {

  @Test
  public void testNeedsUrlEscaping() throws URISyntaxException {
    assertThat(needsUrlEscaping(new URIish("http://host/path"))).isTrue();
    assertThat(needsUrlEscaping(new URIish("https://host/path"))).isTrue();
    assertThat(needsUrlEscaping(new URIish("amazon-s3://config/bucket/path"))).isTrue();

    assertThat(needsUrlEscaping(new URIish("host:path"))).isFalse();
    assertThat(needsUrlEscaping(new URIish("user@host:path"))).isFalse();
    assertThat(needsUrlEscaping(new URIish("git://host/path"))).isFalse();
    assertThat(needsUrlEscaping(new URIish("ssh://host/path"))).isFalse();
  }

  @Test
  public void urlEscaping() {
    assertThat(escape("foo/bar/thing")).isEqualTo("foo/bar/thing");
    assertThat(escape("-- All Projects --")).isEqualTo("--%20All%20Projects%20--");
    assertThat(escape("name/with a space")).isEqualTo("name/with%20a%20space");
    assertThat(escape("name\nwith-LF")).isEqualTo("name%0Awith-LF");
    assertThat(
            escape(
                "key=a-value=1, --option1 \"OPTION_VALUE_1\" --option-2 <option_VALUE-2>"
                    + " --option-without-value"))
        .isEqualTo(
            "key=a-value=1,%20--option1%20%22OPTION_VALUE_1%22%20--option-2%20%3Coption_VALUE-2%3E%20--option-without-value");
  }

  @Test
  public void getHttpsURIWithStrangeProjectName() throws Exception {
    String urlBase = "https://git.server.example.com";
    String url = urlBase + "/${name}.git";
    URIish template = new URIish(url);
    String name =
        "key=a-value=1, --option1 \"OPTION_VALUE_1\" --option-2 <option_VALUE-2>"
            + " --option-without-value";
    String expectedAsciiName =
        "key=a-value=1,%20--option1%20\"OPTION_VALUE_1\"%20--option-2%20<option_VALUE-2>%20--option-without-value";
    String expectedEscapedName =
        "key=a-value=1,%20--option1%20%22OPTION_VALUE_1%22%20--option-2%20%3Coption_VALUE-2%3E%20--option-without-value";
    Project.NameKey project = Project.nameKey(name);
    URIish expanded = Destination.getURI(template, project, "slash", false);

    assertThat(expanded.getPath()).isEqualTo("/" + name + ".git");
    assertThat(expanded.toString()).isEqualTo(urlBase + "/" + expectedEscapedName + ".git");
    assertThat(expanded.toASCIIString()).isEqualTo(urlBase + "/" + expectedAsciiName + ".git");
  }
}
