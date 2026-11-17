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

import com.google.gerrit.entities.Project;
import java.io.File;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalFSTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void shouldCreateReftableRepositoryFromReplicatedGitConfig() throws Exception {
    File repositoryDirectory = new File(temporaryFolder.getRoot(), "reftable.git");
    LocalFS localFS = new LocalFS(new URIish(repositoryDirectory.toURI().toString()));
    String gitConfig =
        "[core]\n\trepositoryFormatVersion = 1\n"
            + "[extensions]\n\trefStorage = reftable\n"
            + "[replication]\n\ttestValue = replicated\n";

    assertThat(
            localFS.createProject(
                Project.nameKey("reftable"), "refs/heads/master", false, gitConfig))
        .isTrue();

    try (Repository repository = new FileRepository(repositoryDirectory)) {
      assertThat(
              repository
                  .getConfig()
                  .getString(
                      ConfigConstants.CONFIG_EXTENSIONS_SECTION,
                      null,
                      ConfigConstants.CONFIG_KEY_REF_STORAGE))
          .isEqualTo(ConfigConstants.CONFIG_REF_STORAGE_REFTABLE);
      assertThat(repository.getConfig().getString("replication", null, "testValue"))
          .isEqualTo("replicated");
    }
  }
}
