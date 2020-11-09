// Copyright (C) 2020 The Android Open Source Project
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

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.SwapAdminApiReplicationModule")
public class SwapAdminApiReplicationIT extends ReplicationDaemon {
  private static final AtomicInteger TEST_FACTORY_CALLED = new AtomicInteger(0);

  @Override
  public void setUpTestPlugin() throws Exception {
    initConfig();
    setReplicationDestination("remote1", "suffix1", Optional.of("not-used-project"));
    super.setUpTestPlugin();
  }

  @Test
  public void shouldUseTestAdminApiFactory() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject(project + "replica");
    String newHead = "refs/heads/newhead";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newHead).create(input);
    gApi.projects().name(project.get()).head(newHead);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, newHead) != null, TEST_PUSH_TIMEOUT);

      Ref targetProjectHead = getRef(repo, Constants.HEAD);
      assertThat(targetProjectHead).isNotNull();
      assertThat(targetProjectHead.getTarget().getName()).isEqualTo(newHead);
    }
    assertThat(TEST_FACTORY_CALLED.get()).isGreaterThan(0);
  }

  /**
   * Factory delegates to the DefaultAdminApiFactory but it increases TEST_FACTORY_CALLED counter
   * each time when AdminApi is created.
   */
  static class TestAdminApiFactory implements AdminApiFactory {
    private final DefaultAdminApiFactory defaultFactry;

    @Inject
    TestAdminApiFactory(DefaultAdminApiFactory defaultFactry) {
      this.defaultFactry = defaultFactry;
    }

    @Override
    public Optional<AdminApi> create(URIish uri) {
      TEST_FACTORY_CALLED.incrementAndGet();
      return defaultFactry.create(uri);
    }
  }

  /** Swaps DefaultAdminApiFactory with TestAdminApiFactory */
  static class TestDestination extends Destination {
    interface Factory extends Destination.Factory {
      @Override
      TestDestination create(DestinationConfiguration cfg);
    }

    @Inject
    protected TestDestination(
        Injector injector,
        PluginUser pluginUser,
        GitRepositoryManager gitRepositoryManager,
        PermissionBackend permissionBackend,
        Provider<CurrentUser> userProvider,
        ProjectCache projectCache,
        GroupBackend groupBackend,
        ReplicationStateListeners stateLog,
        GroupIncludeCache groupIncludeCache,
        DynamicItem<EventDispatcher> eventDispatcher,
        Provider<ReplicationTasksStorage> rts,
        @Assisted DestinationConfiguration cfg) {
      super(
          injector,
          pluginUser,
          gitRepositoryManager,
          permissionBackend,
          userProvider,
          projectCache,
          groupBackend,
          stateLog,
          groupIncludeCache,
          eventDispatcher,
          rts,
          cfg);
    }

    @Override
    protected Class<? extends AdminApiFactory> getAdminApiFactory() {
      return TestAdminApiFactory.class;
    }
  }
}
