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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import java.io.ByteArrayOutputStream;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RemoteSshTest {
  @Mock private SshHelper sshHelper;

  @Test
  public void shouldApplyCompleteGitConfig() throws Exception {
    URIish uri = new URIish("ssh://example.com/repositories/test.git");
    ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
    when(sshHelper.newErrorBufferStream()).thenReturn(errorStream);
    when(sshHelper.executeRemoteSsh(eq(uri), anyString(), eq(errorStream), any())).thenReturn(0);
    String gitConfig =
        "[core]\n\trepositoryFormatVersion = 1\n" + "[extensions]\n\trefStorage = reftable\n";

    assertThat(
            new RemoteSsh(sshHelper, uri)
                .createProject(Project.nameKey("test"), "refs/heads/main", gitConfig))
        .isTrue();

    ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<byte[]> standardInput = ArgumentCaptor.forClass(byte[].class);
    verify(sshHelper)
        .executeRemoteSsh(eq(uri), command.capture(), eq(errorStream), standardInput.capture());
    assertThat(command.getValue()).contains("git init --bare");
    assertThat(command.getValue()).doesNotContain("ref-format");
    assertThat(command.getValue()).contains("cat > '/repositories/test.git/config'");
    assertThat(command.getValue()).doesNotContain(gitConfig);
    assertThat(new String(standardInput.getValue(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo(gitConfig);
    assertThat(command.getValue()).contains("git symbolic-ref HEAD 'refs/heads/main'");
  }
}
