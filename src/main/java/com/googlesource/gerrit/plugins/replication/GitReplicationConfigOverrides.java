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
import static com.googlesource.gerrit.plugins.replication.FanoutConfigResource.removeRemotes;
import static com.googlesource.gerrit.plugins.replication.FileConfigResource.CONFIG_NAME;
import static org.eclipse.jgit.lib.FileMode.REGULAR_FILE;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

public class GitReplicationConfigOverrides implements ReplicationConfigOverrides {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static final String REF_NAME = RefNames.REFS_META + "replication";

  private final Project.NameKey allProjectsName;
  private final GitRepositoryManager repoManager;

  @Inject
  GitReplicationConfigOverrides(
      GitRepositoryManager repoManager, Provider<AllProjectsName> allProjectsNameProvider) {
    this.repoManager = repoManager;
    this.allProjectsName = allProjectsNameProvider.get();
  }

  @Override
  public Config getConfig() {
    try (Repository repo = repoManager.openRepository(allProjectsName);
        RevWalk rw = new RevWalk(repo)) {
      Ref ref = repo.exactRef(REF_NAME);
      if (ref != null) {
        RevTree tree = rw.parseTree(ref.getObjectId());
        Config baseConfig = getBaseConfig(repo, tree);
        addFanoutRemotes(repo, tree, baseConfig);

        return baseConfig;
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot read replication config from git repository");
    } catch (ConfigInvalidException e) {
      logger.atWarning().withCause(e).log("Cannot parse replication config from git repository");
    }
    return new Config();
  }

  @Override
  public String getVersion() {
    try (Repository repo = repoManager.openRepository(allProjectsName)) {
      ObjectId configHead = repo.resolve(REF_NAME);
      if (configHead != null) {
        return configHead.name();
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Could not open replication configuration repository");
    }

    return "";
  }

  private Config getBaseConfig(Repository repo, RevTree tree)
      throws ConfigInvalidException, IOException {
    TreeWalk tw = TreeWalk.forPath(repo, CONFIG_NAME, tree);
    if (tw == null) {
      return new Config();
    }
    return new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
  }

  private void addFanoutRemotes(Repository repo, RevTree tree, Config destination)
      throws IOException, ConfigInvalidException {
    TreeWalk tw = TreeWalk.forPath(repo, CONFIG_DIR, tree);
    if (tw == null) {
      return;
    }

    removeRemotes(destination);

    tw.enterSubtree();
    while (tw.next()) {
      if (tw.getFileMode() == REGULAR_FILE && tw.getNameString().endsWith(".config")) {
        Config remoteConfig = new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
        FanoutConfigResource.addRemoteConfig(tw.getNameString(), remoteConfig, destination);
      }
    }
  }
}
