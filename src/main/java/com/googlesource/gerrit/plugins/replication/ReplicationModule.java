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
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventTypes;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.internal.UniqueAnnotations;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationFailedEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationScheduledEvent;
import com.googlesource.gerrit.plugins.replication.events.ProjectDeletionReplicationSucceededEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.events.RefReplicationDoneEvent;
import com.googlesource.gerrit.plugins.replication.events.ReplicationScheduledEvent;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.jgit.transport.SshSessionFactory;

class ReplicationModule extends AbstractModule {

  private final ReplicationConfigModule configModule;

  @Inject
  public ReplicationModule(ReplicationConfigModule configModule) {
    this.configModule = configModule;
  }

  @Override
  protected void configure() {
    install(configModule);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(ReplicationQueue.class);

    DynamicSet.bind(binder(), GitBatchRefUpdateListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), ProjectDeletedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), HeadUpdatedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), ReplicationRemotesUpdater.class)
        .to(ReplicationRemotesUpdaterImpl.class);

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

    bind(EventBus.class).in(Scopes.SINGLETON);
    bind(ConfigParser.class).to(DestinationConfigParser.class).in(Scopes.SINGLETON);

    DynamicSet.setOf(binder(), ReplicationStateListener.class);
    DynamicSet.bind(binder(), ReplicationStateListener.class).to(ReplicationStateLogger.class);

    EventTypes.register(RefReplicatedEvent.TYPE, RefReplicatedEvent.class);
    EventTypes.register(RefReplicationDoneEvent.TYPE, RefReplicationDoneEvent.class);
    EventTypes.register(ReplicationScheduledEvent.TYPE, ReplicationScheduledEvent.class);
    EventTypes.register(
        ProjectDeletionReplicationScheduledEvent.TYPE,
        ProjectDeletionReplicationScheduledEvent.class);
    EventTypes.register(
        ProjectDeletionReplicationFailedEvent.TYPE, ProjectDeletionReplicationFailedEvent.class);
    EventTypes.register(
        ProjectDeletionReplicationSucceededEvent.TYPE,
        ProjectDeletionReplicationSucceededEvent.class);
    EventTypes.register(
        ProjectDeletionReplicationDoneEvent.TYPE, ProjectDeletionReplicationDoneEvent.class);
    bind(SshSessionFactory.class).toProvider(ReplicationSshSessionFactoryProvider.class);

    bind(TransportFactory.class).to(TransportFactoryImpl.class).in(Scopes.SINGLETON);
    bind(CloseableHttpClient.class).toProvider(HttpClientProvider.class).in(Scopes.SINGLETON);
  }
}
