// Copyright (C) 2023 The Android Open Source Project
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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;
import static com.googlesource.gerrit.plugins.replication.GitReplicationConfigOverrides.REF_NAME;

import com.google.common.collect.ImmutableListMultimap;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.junit.TestRepository.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitReplicationConfigOverridesValidatorTest {
  private static final Provider<AllProjectsName> ALL_PROJECTS_NAME_PROVIDER =
      Providers.of(new AllProjectsName("All-Projects"));

  @Mock ReceiveCommand receiveCommand;
  @Mock IdentifiedUser identifiedUser;
  private final Project allProjects = Project.builder(ALL_PROJECTS_NAME_PROVIDER.get()).build();

  @Test
  public void skipOnDifferentProject() throws Exception {
    Project project = Project.builder(Project.nameKey("other-project")).build();
    try (CommitReceivedEvent event = newEvent(project, GitReplicationConfigOverrides.REF_NAME)) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).isEmpty();
    }
  }

  @Test
  public void skipOnDifferentBranch() throws Exception {
    try (CommitReceivedEvent event = newEvent(allProjects, "refs/heads/master")) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).isEmpty();
    }
  }

  @Test
  public void noMessagesOnValidReplicationConfig() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder ->
                commitBuilder.add(CONFIG_NAME, "[replication]\n\rmaxRetires = 8").create())) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).isEmpty();
    }
  }

  @Test
  public void noMessagesOnValidFanoutConfig() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder ->
                commitBuilder
                    .add(CONFIG_DIR + "remote.config", "[remote]\n\tmirror = true")
                    .create())) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).isEmpty();
    }
  }

  @Test
  public void throwsOnInvalidMainConfig() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder -> commitBuilder.add(CONFIG_NAME, "[[]invalidConfig").create())) {

      CommitValidationException exception =
          assertThrows(
              CommitValidationException.class, () -> newValidator().onCommitReceived(event));
      assertThat(exception.getMessages()).hasSize(1);
      assertThat(exception.getMessages().get(0).isError()).isTrue();
      assertThat(exception.getMessages().get(0).getMessage())
          .isEqualTo("Cannot parse replication.config file.");
    }
  }

  @Test
  public void throwsOnInvalidFanoutConfig() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder ->
                commitBuilder.add(CONFIG_DIR + "remote.config", "[[]invalidConfig").create())) {

      CommitValidationException exception =
          assertThrows(
              CommitValidationException.class, () -> newValidator().onCommitReceived(event));
      assertThat(exception.getMessages()).hasSize(1);
      assertThat(exception.getMessages().get(0).isError()).isTrue();
      assertThat(exception.getMessages().get(0).getMessage())
          .isEqualTo("Cannot parse replication/remote.config file.");
    }
  }

  @Test
  public void warningWhenMainConfigHasRemoteSectionAndFanoutIsPresent() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder ->
                commitBuilder
                    .add(
                        CONFIG_NAME,
                        "[replication]\n\rmaxRetires = 8\n\t[remote \"test\"]\n\tmirror = true")
                    .add(CONFIG_DIR + "remote.config", "[remote]\n\tmirror = true")
                    .create())) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).hasSize(1);
      assertThat(actual.get(0).getType()).isEqualTo(ValidationMessage.Type.HINT);
      assertThat(actual.get(0).getMessage())
          .isEqualTo(
              "When replication directory is present replication.config file cannot contain remote"
                  + " configuration. Ignoring: test.");
    }
  }

  @Test
  public void warningOnMultipleRemoteSectionsInFanoutConfig() throws Exception {
    try (CommitReceivedEvent event =
        newEventForReplication(
            commitBuilder ->
                commitBuilder
                    .add(
                        CONFIG_DIR + "remote.config",
                        "[remote \"one\"]\n\tmirror = true\n[remote \"two\"]\n\tmirror = true")
                    .create())) {

      List<CommitValidationMessage> actual = newValidator().onCommitReceived(event);

      assertThat(actual).hasSize(1);
      assertThat(actual.get(0).getType()).isEqualTo(ValidationMessage.Type.HINT);
      assertThat(actual.get(0).getMessage())
          .isEqualTo(
              "Remote replication configuration file replication/remote.config cannot contain"
                  + " remote subsections.");
    }
  }

  private GitReplicationConfigOverridesValidator newValidator() {
    return new GitReplicationConfigOverridesValidator(ALL_PROJECTS_NAME_PROVIDER);
  }

  private CommitReceivedEvent newEvent(Project project, String ref) throws Exception {
    Repository repo = new InMemoryRepository(new DfsRepositoryDescription(project.getName()));
    repo.create(true);
    RevCommit commit = new TestRepository<>(repo).branch(ref).commit().add("test.txt", "").create();

    return new CommitReceivedEvent(
        receiveCommand,
        project,
        ref,
        ImmutableListMultimap.of(),
        new Config(),
        repo.newObjectReader(),
        commit,
        identifiedUser);
  }

  interface RepoMutations {
    RevCommit apply(CommitBuilder commitBuilder) throws Exception;
  }

  private CommitReceivedEvent newEventForReplication(RepoMutations repoMutations) throws Exception {
    Repository repo =
        new InMemoryRepository(
            new DfsRepositoryDescription(ALL_PROJECTS_NAME_PROVIDER.get().get()));
    repo.create(true);
    RevCommit commit = repoMutations.apply(new TestRepository<>(repo).branch(REF_NAME).commit());

    return new CommitReceivedEvent(
        receiveCommand,
        allProjects,
        REF_NAME,
        ImmutableListMultimap.of(),
        new Config(),
        repo.newObjectReader(),
        commit,
        identifiedUser);
  }
}
