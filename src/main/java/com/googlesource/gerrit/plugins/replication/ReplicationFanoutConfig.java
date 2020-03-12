package com.googlesource.gerrit.plugins.replication;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.server.config.SitePaths;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.util.FS;

public class ReplicationFanoutConfig implements ReplicationConfig {

  private ReplicationFileBasedConfig mainReplicationConfig;
  List<Config> replicationRemoteconfigs = Collections.emptyList();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private Path replicationConfigDirPath;

  public ReplicationFanoutConfig(SitePaths site, @PluginData Path pluginDataDir)
      throws IOException {
    this.replicationConfigDirPath = site.etc_dir.resolve("replication.remotes.d");
    this.mainReplicationConfig = new ReplicationFileBasedConfig(site, pluginDataDir);

    try {
      mainReplicationConfig.getConfig().load();
    } catch (ConfigInvalidException e) {
      throw new IllegalStateException(e);
    }

    try (Stream<Path> files = Files.walk(replicationConfigDirPath)) {
      replicationRemoteconfigs =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().toLowerCase().endsWith(".config"))
              .map(path -> new FileBasedConfig(path.toFile(), FS.DETECTED))
              .map(
                  cfg -> {
                    try {
                      cfg.load();
                    } catch (IOException | ConfigInvalidException e) {
                      throw new IllegalStateException(e);
                    }
                    return cfg;
                  })
              .collect(Collectors.toList());
    }
  }

  @Override
  public boolean isReplicateAllOnPluginStart() {
    return mainReplicationConfig.isReplicateAllOnPluginStart();
  }

  @Override
  public boolean isDefaultForceUpdate() {
    return mainReplicationConfig.isDefaultForceUpdate();
  }

  @Override
  public int getMaxRefsToLog() {
    return mainReplicationConfig.getMaxRefsToLog();
  }

  @Override
  public Path getEventsDirectory() {
    return mainReplicationConfig.getEventsDirectory();
  }

  @Override
  public int getSshConnectionTimeout() {
    return mainReplicationConfig.getSshConnectionTimeout();
  }

  @Override
  public int getSshCommandTimeout() {
    return mainReplicationConfig.getSshCommandTimeout();
  }

  @Override
  public String getVersion() {
    return Long.toString(
        Math.max(
            Long.valueOf(mainReplicationConfig.getVersion()),
            getRemoteReplicationDirectoryVersion()));
  }

  @Override
  public Config getConfig() {
    return mainReplicationConfig.getConfig();
  }

  @Override
  public List<RemoteConfig> getRemoteConfigs() throws ConfigInvalidException {

    List<RemoteConfig> cfgs = mainReplicationConfig.getRemoteConfigs();

    List<RemoteConfig> remoteCfg =
        replicationRemoteconfigs.stream()
            .flatMap(
                cfg -> {
                  Set<String> names = cfg.getSubsections("remote");
                  List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
                  for (String name : names) {
                    try {
                      result.add(new RemoteConfig(cfg, name));
                    } catch (URISyntaxException e) {
                      throw new IllegalStateException(
                          String.format("remote %s has invalid URL", name), e);
                    }
                  }
                  return result.stream();
                })
            .collect(Collectors.toList());
    cfgs.addAll(remoteCfg);
    return cfgs;
  }

  private Long getRemoteReplicationDirectoryVersion() {
    try {
      if (!Files.exists(replicationConfigDirPath)) {
        return Long.MIN_VALUE;
      }
      return Files.getLastModifiedTime(replicationConfigDirPath).toMillis();

    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Cannot read configuration version for dynamic replication config files");
      return Long.MIN_VALUE;
    }
  }
}
