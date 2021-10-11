package com.googlesource.gerrit.plugins.replication;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.WaitUtil;
import com.google.gerrit.entities.Project;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationPushInBatchesIT extends ReplicationDaemon {

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    config.setInt("gerrit", null, "pushBatchSize", 1);
    config.save();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateWithPushBatchSizeSet() throws Exception {
    Project.NameKey targetProject = createTestProject(project + "replica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    // creating a change results in 2 refs creation therefore it already qualifies for push in two
    // batches of size 1 each
    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, sourceRef) != null, Duration.ofSeconds(60));

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }
}
