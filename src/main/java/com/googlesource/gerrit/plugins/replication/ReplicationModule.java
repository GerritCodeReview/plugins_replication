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

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventTypes;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.internal.UniqueAnnotations;
import org.eclipse.jgit.transport.SshSessionFactory;

class ReplicationModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(DestinationFactory.class).in(Scopes.SINGLETON);
    bind(ReplicationQueue.class).in(Scopes.SINGLETON);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(ReplicationQueue.class);

    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), NewProjectCreatedListener.class).to(ReplicationQueue.class);
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
    install(new FactoryModuleBuilder().build(RemoteSiteUser.Factory.class));
    install(new FactoryModuleBuilder().build(ReplicationState.Factory.class));

    bind(ReplicationConfig.class).to(AutoReloadConfigDecorator.class);
    bind(ReplicationStateListener.class).to(ReplicationStateLogger.class);

    EventTypes.register(RefReplicatedEvent.TYPE, RefReplicatedEvent.class);
    EventTypes.register(RefReplicationDoneEvent.TYPE, RefReplicationDoneEvent.class);
    EventTypes.register(ReplicationScheduledEvent.TYPE, ReplicationScheduledEvent.class);
    bind(SshSessionFactory.class).toProvider(ReplicationSshSessionFactoryProvider.class);

    bind(AdminApiFactory.class);
  }
}
