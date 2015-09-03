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

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ReplicationFileBasedConfig implements ReplicationConfig {
  private static final Logger log = LoggerFactory.getLogger(ReplicationFileBasedConfig.class);
  private List<Destination> destinations;
  private Path cfgPath;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private int sshCommandTimeout;
  private int sshConnectionTimeout;
  private final FileBasedConfig config;

  @Inject
  public ReplicationFileBasedConfig(SitePaths site, DestinationFactory destinationFactory)
      throws ConfigInvalidException, IOException {
    this.cfgPath = site.etc_dir.resolve("replication.config");
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    this.destinations = allDestinations(destinationFactory);
  }

  /*
   * (non-Javadoc)
   * @see
   * com.googlesource.gerrit.plugins.replication.ReplicationConfig#getDestinations
   * (com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType)
   */
  @Override
  public List<Destination> getDestinations(FilterType filterType) {
    Predicate<? super Destination> filter;
    switch (filterType) {
      case PROJECT_CREATION:
        filter = dest -> dest.isCreateMissingRepos();
        break;
      case PROJECT_DELETION:
        filter = dest -> dest.isReplicateProjectDeletions();
        break;
      case ALL:
      default:
        filter = dest -> true;
        break;
    }
    return destinations.stream().filter(Objects::nonNull).filter(filter).collect(toList());
  }

  private List<Destination> allDestinations(DestinationFactory destinationFactory)
      throws ConfigInvalidException, IOException {
    if (!config.getFile().exists()) {
      log.warn("Config file {} does not exist; not replicating", config.getFile());
      return Collections.emptyList();
    }
    if (config.getFile().length() == 0) {
      log.info("Config file {} is empty; not replicating", config.getFile());
      return Collections.emptyList();
    }

    try {
      config.load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(
          String.format("Config file %s is invalid: %s", config.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      throw new IOException(
          String.format("Cannot read %s: %s", config.getFile(), e.getMessage()), e);
    }

    replicateAllOnPluginStart = config.getBoolean("gerrit", "replicateOnStartup", true);

    defaultForceUpdate = config.getBoolean("gerrit", "defaultForceUpdate", false);

    sshCommandTimeout =
        (int) ConfigUtil.getTimeUnit(config, "gerrit", null, "sshCommandTimeout", 0, SECONDS);
    sshConnectionTimeout =
        (int)
            SECONDS.toMillis(
                ConfigUtil.getTimeUnit(config, "gerrit", null, "sshConnectionTimeout", 2, MINUTES));

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
        c.addPushRefSpec(
            new RefSpec()
                .setSourceDestination("refs/*", "refs/*")
                .setForceUpdate(defaultForceUpdate));
      }

      Destination destination = destinationFactory.create(new DestinationConfiguration(c, config));

      if (!destination.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format(
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

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg) throws ConfigInvalidException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(
            String.format("remote %s has invalid URL in %s", name, cfg.getFile()));
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

  @Override
  public void startup(WorkQueue workQueue) {
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }

  @Override
  public int getSshConnectionTimeout() {
    return sshConnectionTimeout;
  }

  @Override
  public int getSshCommandTimeout() {
    return sshCommandTimeout;
  }
}
