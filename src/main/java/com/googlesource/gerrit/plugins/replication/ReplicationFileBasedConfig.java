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

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.common.FileUtil;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class ReplicationFileBasedConfig implements ReplicationConfig {
  static final Logger log = LoggerFactory.getLogger(ReplicationFileBasedConfig.class);
  private List<Destination> destinations;
  private Path cfgPath;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private Injector injector;
  private final RemoteSiteUser.Factory replicationUserFactory;
  private final PluginUser pluginUser;
  private final GitRepositoryManager gitRepositoryManager;
  private final GroupBackend groupBackend;
  private final FileBasedConfig config;
  private long loadedAt;
  private static Map<RemoteConfig, FileBasedConfig> configMap = new HashMap<>();
  private static List<Path> allConfigPath = new ArrayList<>();
  private final ReplicationStateListener stateLog;

  @Inject
  public ReplicationFileBasedConfig(final Injector injector, final SitePaths site,
      final RemoteSiteUser.Factory ruf, final PluginUser pu,
      final GitRepositoryManager grm,
      final GroupBackend gb,
      final ReplicationStateListener stateLog) throws ConfigInvalidException, IOException {
    this.cfgPath = site.etc_dir.resolve("replication.config");
    this.injector = injector;
    this.replicationUserFactory = ruf;
    this.pluginUser = pu;
    this.gitRepositoryManager = grm;
    this.groupBackend = gb;
    configMap.clear();
    allConfigPath.clear();
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    allConfigPath.add(cfgPath);
    this.destinations = allDestinations();
    this.stateLog = stateLog;
    this.loadedAt = System.currentTimeMillis();
  }

  /*
   * (non-Javadoc)
   * @see
   * com.googlesource.gerrit.plugins.replication.ReplicationConfig#getDestinations
   * (com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType)
   */
  @Override
  public List<Destination> getDestinations(FilterType filterType) {
    Predicate<Destination> filter;
    switch (filterType) {
      case PROJECT_CREATION :
        filter = new Predicate<Destination>() {

          @Override
          public boolean apply(Destination dest) {
            if (dest == null || !dest.isCreateMissingRepos()) {
              return false;
            }
            return true;
          }
        };
        break;
      case PROJECT_DELETION :
        filter = new Predicate<Destination>() {

          @Override
          public boolean apply(Destination dest) {
            if (dest == null || !dest.isReplicateProjectDeletions()) {
              return false;
            }
            return true;
          }
        };
        break;
      case ALL :
        return destinations;
      default :
        return destinations;
    }
    return FluentIterable.from(destinations).filter(filter).toList();
  }

  private static boolean loadConfigFile(FileBasedConfig cfg)
      throws ConfigInvalidException, IOException {
    if (!cfg.getFile().exists()) {
      log.warn("Config file " + cfg.getFile() + " does not exist; not replicating");
    } else if (cfg.getFile().length() == 0) {
      log.info("Config file " + cfg.getFile() + " is empty; not replicating");
    } else {
      try {
        cfg.load();
        return true;
      } catch (ConfigInvalidException e) {
        throw new ConfigInvalidException(String.format(
            "Config file %s is invalid: %s", cfg.getFile(), e.getMessage()), e);
      } catch (IOException e) {
        throw new IOException(String.format("Cannot read %s: %s", cfg.getFile(),
            e.getMessage()), e);
      }
    }
    return false;
  }

  private List<Destination> allDestinations()
      throws ConfigInvalidException, IOException {
    if (!loadConfigFile(config)) {
      return Collections.emptyList();
    }

    replicateAllOnPluginStart =
        config.getBoolean("gerrit", "replicateOnStartup", true);

    defaultForceUpdate =
        config.getBoolean("gerrit", "defaultForceUpdate", false);

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
            .setForceUpdate(defaultForceUpdate));
      }

      Destination destination =
          new Destination(injector, c, configMap.get(c), replicationUserFactory,
              pluginUser, gitRepositoryManager, groupBackend, stateLog);

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

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isDefaultForceUpdate()
   */
  @Override
  public boolean isDefaultForceUpdate() {
    return defaultForceUpdate;
  }

  private static List<RemoteConfig> parseIncludedRemotes(FileBasedConfig cfg)
     throws ConfigInvalidException, IOException {
    String[] subConfigFiles = cfg.getStringList("include", "remote_file", "file");
    List<RemoteConfig> result = new ArrayList<>();
    for (String fileName : subConfigFiles) {
      File configFile = new File(cfg.getFile().getParent(), fileName);
      FileBasedConfig subConfig = new FileBasedConfig(configFile, FS.DETECTED);
      if (!loadConfigFile(subConfig)) {
        continue;
      }
      allConfigPath.add(configFile.toPath());

      Set<String> names = subConfig.getSubsections("remote");
      Set<String> supers = cfg.getSubsections("remote");
      for (String name : names) {
        try {
          if (!supers.contains(name)) {
            RemoteConfig rc = new RemoteConfig(subConfig, name);
            configMap.put(rc, subConfig);
            result.add(rc);
          }
        } catch (URISyntaxException e) {
          throw new ConfigInvalidException(String.format(
              "remote %s has invalid URL in %s", name, configFile));
        }
      }
    }

    return result;
  }

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg)
      throws ConfigInvalidException, IOException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> included = parseIncludedRemotes(cfg);
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        RemoteConfig rc = new RemoteConfig(cfg, name);
        configMap.put(rc, cfg);
        result.add(rc);
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(String.format(
            "remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }

    result.addAll(included);
    return result;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isEmpty()
   */
  @Override
  public boolean isEmpty() {
    return destinations.isEmpty();
  }

  Path getCfgPath() {
    return cfgPath;
  }

  @Override
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

  public boolean isConfigChanged() {
    long last = 0;
    for (Path p : allConfigPath) {
      long ts = FileUtil.lastModified(p);
      if (ts > last) {
        last = ts;
      }
    }
    return loadedAt < last;
  }

  @Override
  public void startup(WorkQueue workQueue) {
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }
}
