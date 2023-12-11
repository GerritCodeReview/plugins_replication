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

import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.CONFIG_DIR;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

public class GitReplicationConfigOverridesValidator implements CommitValidationListener {
  private final Project.NameKey allProjectsName;

  @Inject
  GitReplicationConfigOverridesValidator(Provider<AllProjectsName> allProjectsNameProvider) {
    allProjectsName = allProjectsNameProvider.get();
  }

  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent)
      throws CommitValidationException {
    if (!allProjectsName.equals(receiveEvent.getProjectNameKey())
        || !GitReplicationConfigOverrides.REF_NAME.equals(
            receiveEvent.getBranchNameKey().branch())) {
      return Collections.emptyList();
    }

    return verifyMainConfig(receiveEvent);
  }

  private List<CommitValidationMessage> verifyMainConfig(CommitReceivedEvent receiveEvent)
      throws CommitValidationException {
    List<CommitValidationMessage> messages = new ArrayList<>();
    ObjectReader reader = receiveEvent.revWalk.getObjectReader();
    try {
      Config baseConfig = new Config();
      RevTree tree = receiveEvent.revWalk.parseTree(receiveEvent.commit);
      TreeWalk tw = TreeWalk.forPath(reader, CONFIG_NAME, tree);

      if (tw != null) {
        baseConfig = loadConfig(reader, tw);
      }

      messages.addAll(verifyFanoutConfiguration(reader, tree, baseConfig));
    } catch (IOException e) {
      String msg = "Cannot read incoming commit";
      throw new CommitValidationException(msg, newErrorMessage(msg), e);
    } catch (ConfigInvalidException e) {
      String msg = "Cannot parse replication.config file.";
      throw new CommitValidationException(msg, newErrorMessage(msg), e);
    }

    return messages;
  }

  private List<CommitValidationMessage> verifyFanoutConfiguration(
      ObjectReader reader, RevTree tree, Config baseConfig)
      throws IOException, CommitValidationException {
    List<CommitValidationMessage> messages = new ArrayList<>();
    TreeWalk tw = TreeWalk.forPath(reader, CONFIG_DIR, tree);
    if (tw != null) {
      Set<String> remoteNames = baseConfig.getSubsections("remote");
      if (!remoteNames.isEmpty()) {
        messages.add(
            newHintMessage(
                String.format(
                    "When replication directory is present replication.config file cannot contain"
                        + " remote configuration. Ignoring: %s.",
                    String.join(",", remoteNames))));
      }
      tw.enterSubtree();

      while (tw.next()) {
        if (!tw.getNameString().endsWith(".config")) {
          continue;
        }
        messages.addAll(parseConfig(reader, tw));
      }
    }

    return messages;
  }

  private List<CommitValidationMessage> parseConfig(ObjectReader reader, TreeWalk tw)
      throws CommitValidationException {
    String fileName = tw.getNameString();
    List<CommitValidationMessage> messages = new ArrayList<>();
    try {
      Config config = loadConfig(reader, tw);
      if (config.getSections().size() != 1 || !config.getSections().contains("remote")) {
        messages.add(
            newHintMessage(
                String.format(
                    "Remote replication configuration file %s%s must contain only one remote"
                        + " section.",
                    CONFIG_DIR, fileName)));
      }
      if (!config.getSubsections("remote").isEmpty()) {
        messages.add(
            newHintMessage(
                String.format(
                    "Remote replication configuration file %s%s cannot contain remote subsections.",
                    CONFIG_DIR, fileName)));
      }
    } catch (IOException e) {
      String msg = String.format("Cannot open %s%s file.", CONFIG_DIR, tw.getNameString());
      throw new CommitValidationException(msg, newErrorMessage(msg), e);
    } catch (ConfigInvalidException e) {
      String msg = String.format("Cannot parse %s%s file.", CONFIG_DIR, tw.getNameString());
      throw new CommitValidationException(msg, newErrorMessage(msg), e);
    }

    return messages;
  }

  private Config loadConfig(ObjectReader reader, TreeWalk tw)
      throws ConfigInvalidException, IOException {
    ObjectLoader loader = reader.open(tw.getObjectId(0), Constants.OBJ_BLOB);
    String content = new String(loader.getCachedBytes(), StandardCharsets.UTF_8);
    Config config = new Config();
    config.fromText(content);
    return config;
  }

  private CommitValidationMessage newHintMessage(String msg) {
    return new CommitValidationMessage(msg, CommitValidationMessage.Type.HINT);
  }

  private CommitValidationMessage newErrorMessage(String msg) {
    return new CommitValidationMessage(msg, true);
  }
}
