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

import com.google.gerrit.lifecycle.LifecycleListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.jcraft.jsch.Session;

import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.SshSessionFactory;

import java.util.concurrent.TimeUnit;

@Singleton
class OnStartStop implements LifecycleListener {
  private final PushAll.Factory pushAll;
  private final RunningReplicationQueue queue;
  private SshSessionFactory oldFactory;

  @Inject
  OnStartStop(PushAll.Factory pushAll, RunningReplicationQueue queue) {
    this.pushAll = pushAll;
    this.queue = queue;
  }

  @Override
  public void start() {
    oldFactory = SshSessionFactory.getInstance();

    // Install our own factory which always runs in batch mode, as we
    // have no UI available for interactive prompting.
    SshSessionFactory.setInstance(new JschConfigSessionFactory() {
      @Override
      protected void configure(OpenSshConfig.Host hc, Session session) {
        // Default configuration is batch mode.
      }
    });
    queue.start();

    if (queue.replicateAllOnPluginStart) {
      pushAll.create(null).start(30, TimeUnit.SECONDS);
    }
  }

  @Override
  public void stop() {
    queue.stop();
    SshSessionFactory.setInstance(oldFactory);
  }
}
