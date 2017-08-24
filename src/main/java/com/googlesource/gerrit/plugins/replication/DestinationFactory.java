// Copyright (C) 2016 The Android Open Source Project
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
import com.google.inject.Singleton;

@Singleton
public class DestinationFactory {
  private final Injector injector;
  private final RemoteSiteUser.Factory replicationUserFactory;
  private final PluginUser pluginUser;
  private final GitRepositoryManager gitRepositoryManager;
  private final PermissionBackend permissionBackend;
  private final Provider<CurrentUser> userProvider;
  private final ProjectCache projectCache;
  private final GroupBackend groupBackend;
  private final ReplicationStateListener stateLog;
  private final GroupIncludeCache groupIncludeCache;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  @Inject
  public DestinationFactory(
      Injector injector,
      RemoteSiteUser.Factory replicationUserFactory,
      PluginUser pluginUser,
      GitRepositoryManager gitRepositoryManager,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> userProvider,
      ProjectCache projectCache,
      GroupBackend groupBackend,
      ReplicationStateListener stateLog,
      GroupIncludeCache groupIncludeCache,
      DynamicItem<EventDispatcher> eventDispatcher) {
    this.injector = injector;
    this.replicationUserFactory = replicationUserFactory;
    this.pluginUser = pluginUser;
    this.gitRepositoryManager = gitRepositoryManager;
    this.permissionBackend = permissionBackend;
    this.userProvider = userProvider;
    this.projectCache = projectCache;
    this.groupBackend = groupBackend;
    this.stateLog = stateLog;
    this.groupIncludeCache = groupIncludeCache;
    this.eventDispatcher = eventDispatcher;
  }

  Destination create(DestinationConfiguration config) {
    return new Destination(
        injector,
        config,
        replicationUserFactory,
        pluginUser,
        gitRepositoryManager,
        permissionBackend,
        userProvider,
        projectCache,
        groupBackend,
        stateLog,
        groupIncludeCache,
        eventDispatcher);
  }
}
