// Copyright (C) 2012 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.StartReplicationCapability.START_REPLICATION;

import com.google.common.eventbus.EventBus;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.EventTypes;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.internal.UniqueAnnotations;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.util.FS;

class ReplicationModule extends AbstractModule {
  private final Path cfgPath;

  @Inject
  public ReplicationModule(SitePaths site) {
    this.cfgPath = site.etc_dir.resolve("replication.config");
  }

  @Override
  protected void configure() {
    install(new FactoryModuleBuilder().build(Destination.Factory.class));
    bind(ReplicationQueue.class).in(Scopes.SINGLETON);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(ReplicationQueue.class);

    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), ProjectDeletedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), HeadUpdatedListener.class).to(ReplicationQueue.class);

    bind(OnStartStop.class).in(Scopes.SINGLETON);
    bind(LifecycleListener.class).annotatedWith(UniqueAnnotations.create()).to(OnStartStop.class);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(ReplicationLogFile.class);
    bind(CredentialsFactory.class)
        .to(AutoReloadSecureCredentialsFactoryDecorator.class)
        .in(Scopes.SINGLETON);
    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(START_REPLICATION))
        .to(StartReplicationCapability.class);

    install(new FactoryModuleBuilder().build(PushAll.Factory.class));
    install(new FactoryModuleBuilder().build(ReplicationState.Factory.class));

    bind(EventBus.class).in(Scopes.SINGLETON);
    bind(ReplicationDestinations.class).to(DestinationsCollection.class);

    if (getReplicationConfig().getBoolean("gerrit", "autoReload", false)) {
      bind(ReplicationConfig.class).to(AutoReloadConfigDecorator.class);
      bind(LifecycleListener.class)
          .annotatedWith(UniqueAnnotations.create())
          .to(AutoReloadConfigDecorator.class);
    } else {
      bind(ReplicationConfig.class).to(ReplicationFileBasedConfig.class);
    }

    DynamicSet.setOf(binder(), ReplicationStateListener.class);
    DynamicSet.bind(binder(), ReplicationStateListener.class).to(ReplicationStateLogger.class);

    EventTypes.register(RefReplicatedEvent.TYPE, RefReplicatedEvent.class);
    EventTypes.register(RefReplicationDoneEvent.TYPE, RefReplicationDoneEvent.class);
    EventTypes.register(ReplicationScheduledEvent.TYPE, ReplicationScheduledEvent.class);
    bind(SshSessionFactory.class).toProvider(ReplicationSshSessionFactoryProvider.class);
  }

  private FileBasedConfig getReplicationConfig() {
    File replicationConfigFile = this.cfgPath.toFile();
    FileBasedConfig config = new FileBasedConfig(replicationConfigFile, FS.DETECTED);
    try {
      config.load();
    } catch (IOException | ConfigInvalidException e) {
      throw new ProvisionException("Unable to load " + replicationConfigFile.getAbsolutePath(), e);
    }
    return config;
  }
}
