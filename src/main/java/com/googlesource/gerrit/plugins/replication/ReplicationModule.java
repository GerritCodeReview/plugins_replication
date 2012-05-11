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

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.config.FactoryModule;

class ReplicationModule extends FactoryModule {
  @Override
  protected void configure() {
    bind(RunningReplicationQueue.class);
    factory(PushAll.Factory.class);
    factory(RemoteSiteUser.Factory.class);

    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
        .to(RunningReplicationQueue.class);

    DynamicSet.bind(binder(), NewProjectCreatedListener.class)
        .to(RunningReplicationQueue.class);

    install(new LifecycleModule() {
      @Override
      protected void configure() {
        listener().to(OnStartStop.class);
      }
    });
  }
}
