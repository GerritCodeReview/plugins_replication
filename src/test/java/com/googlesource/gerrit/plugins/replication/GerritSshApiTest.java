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
public class GerritSshApiTest {
  @Mock private SshHelper sshHelper;

  @Test
  public void shouldSendCompleteGitConfig() throws Exception {
    URIish uri = new URIish("gerrit+ssh://example.com:29418/");
    ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
    String gitConfig =
        "[core]\n\trepositoryFormatVersion = 1\n"
            + "[extensions]\n\trefStorage = reftable\n"
            + "[custom]\n\tvalue = preserved\n";
    when(sshHelper.newErrorBufferStream()).thenReturn(errorStream);
    when(sshHelper.executeRemoteSsh(any(URIish.class), anyString(), eq(errorStream))).thenReturn(0);

    assertThat(
            new GerritSshApi(sshHelper, uri)
                .createProject(Project.nameKey("project"), "refs/heads/main", gitConfig))
        .isTrue();

    ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
    verify(sshHelper).executeRemoteSsh(any(URIish.class), command.capture(), eq(errorStream));
    assertThat(command.getValue()).contains("gerrit create-project");
    assertThat(command.getValue()).contains("--git-config");
    assertThat(command.getValue()).contains(gitConfig);
    assertThat(command.getValue()).contains("project");
  }
}
