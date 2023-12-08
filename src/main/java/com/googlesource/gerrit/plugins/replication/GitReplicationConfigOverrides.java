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

import com.google.common.flogger.FluentLogger;
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

public class GitReplicationConfigOverrides implements ReplicationConfigOverrides{
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String REF_NAME = "refs/meta/replication";

  private final GitRepositoryManager repoManager;
  private final Provider<AllProjectsName> allProjectsNameProvider;

  @Inject
  GitReplicationConfigOverrides(GitRepositoryManager repoManager, Provider<AllProjectsName> allProjectsNameProvider){
    this.repoManager = repoManager;
    this.allProjectsNameProvider = allProjectsNameProvider;
  }

  @Override
  public Config getConfig() {
    try (Repository repo = repoManager.openRepository(allProjectsNameProvider.get());
        RevWalk rw = new RevWalk(repo)) {
      Ref ref = repo.exactRef(REF_NAME);
      if (ref == null) {
        return new Config();
      }
      RevTree tree = rw.parseTree(ref.getObjectId());
      TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), FileConfigResource.CONFIG_NAME, tree);
      if (tw == null) {
        return new Config();
      }
      return new BlobBasedConfig(new Config(), repo, tw.getObjectId(0));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot read replication config from git repository");
    } catch (ConfigInvalidException e) {
      logger.atWarning().withCause(e).log("Cannot parse replication config from git repository");
    }
    return new Config();
  }

  @Override
  public String getVersion() {
    try (Repository repo = repoManager.openRepository(allProjectsNameProvider.get())) {
      ObjectId configHead = repo.resolve(REF_NAME);
      if (configHead != null) {
        return configHead.name();
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Could not open replication configuration repository");
    }

    return "";
  }
}
