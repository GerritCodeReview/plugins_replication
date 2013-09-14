// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Singleton
public class ReplicationFileBasedConfig implements ReplicationConfig {
  static final Logger log = LoggerFactory.getLogger(ReplicationFileBasedConfig.class);
  private List<Destination> destinations;
  private File cfgPath;
  private boolean replicateAllOnPluginStart;
  private Injector injector;
  private final SchemaFactory<ReviewDb> database;
  private final RemoteSiteUser.Factory replicationUserFactory;
  private final PluginUser pluginUser;
  private final GitRepositoryManager gitRepositoryManager;
  private final GroupBackend groupBackend;
  private final FileBasedConfig config;

  @Inject
  public ReplicationFileBasedConfig(final Injector injector, final SitePaths site,
      final RemoteSiteUser.Factory ruf, final PluginUser pu,
      final SchemaFactory<ReviewDb> db, final GitRepositoryManager grm,
      final GroupBackend gb) throws ConfigInvalidException, IOException {
    this.cfgPath = new File(site.etc_dir, "replication.config");
    this.injector = injector;
    this.replicationUserFactory = ruf;
    this.pluginUser = pu;
    this.database = db;
    this.gitRepositoryManager = grm;
    this.groupBackend = gb;
    this.config = new FileBasedConfig(cfgPath, FS.DETECTED);
    this.destinations = allDestinations();
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#getDestinations()
   */
  @Override
  public List<Destination> getDestinations() {
    return destinations;
  }

  private List<Destination> allDestinations()
      throws ConfigInvalidException, IOException {
    if (!config.getFile().exists()) {
      log.warn("Config file " + config.getFile() + "does not exist; not replicating");
      return Collections.emptyList();
    }
    if (config.getFile().length() == 0) {
      log.info("Config file " + config.getFile() + " is empty; not replicating");
      return Collections.emptyList();
    }

    try {
      config.load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(String.format(
          "Config file %s is invalid: %s", config.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      throw new IOException(String.format("Cannot read %s: %s", config.getFile(),
          e.getMessage()), e);
    }

    replicateAllOnPluginStart =
        config.getBoolean("gerrit", "replicateOnStartup", true);

    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfig c : allRemotes(config)) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // If destination for push is not set assume equal to source.
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        c.addPushRefSpec(new RefSpec().setSourceDestination("refs/*", "refs/*")
            .setForceUpdate(true));
      }

      Destination destination =
          new Destination(injector, c, config, database, replicationUserFactory,
              pluginUser, gitRepositoryManager, groupBackend);

      if (!destination.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(String.format(
                "remote.%s.url \"%s\" lacks ${name} placeholder in %s",
                c.getName(), u, config.getFile()));
          }
        }
      }

      dest.add(destination);
    }
    return dest.build();
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isReplicateAllOnPluginStart()
   */
  @Override
  public boolean isReplicateAllOnPluginStart() {
    return replicateAllOnPluginStart;
  }

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg)
      throws ConfigInvalidException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(String.format(
            "remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }
    return result;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isEmpty()
   */
  @Override
  public boolean isEmpty() {
    return destinations.isEmpty();
  }

  File getCfgPath() {
    return cfgPath;
  }

  public int shutdown() {
    int discarded = 0;
    for (Destination cfg : destinations) {
      discarded += cfg.shutdown();
    }
    return discarded;
  }

  FileBasedConfig getConfig() {
    return config;
  }

  @Override
  public void startup(WorkQueue workQueue) {
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }
}
