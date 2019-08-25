package com.googlesource.gerrit.plugins.replication;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.nio.file.Path;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationIT extends LightweightPluginDaemonTest {

  @Inject private SitePaths sitePaths;

  private Path gitPath;

  FileBasedConfig config;

  @Override
  public void setUpTestPlugin() throws Exception {
    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.save();

    gitPath = sitePaths.site_path.resolve("git");

    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateNewProjects() throws Exception {
    config.setString("remote", "foo", "url", gitPath.resolve("${name}-replica.git").toString());
    config.save();

    reloadConfig();

    ProjectInfo sourceProject = gApi.projects().create("fooProject").get();
    ProjectInfo replicaProject = null;
    while (replicaProject == null) {
      boolean replicaExists = gitPath.resolve("fooProject-replica.git").toFile().isDirectory();
      if (replicaExists) {
        try {
          replicaProject = gApi.projects().name("fooProject-replica").get();
        } catch (ResourceNotFoundException e) {
        }
      } else {
        Thread.sleep(1000);
      }
    }
  }

  private void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }
}
