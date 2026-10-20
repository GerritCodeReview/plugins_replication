// Copyright (C) 2026 The Android Open Source Project
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

import static org.apache.sshd.core.CoreModuleProperties.NIO_WORKERS;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import org.apache.sshd.client.SshClient;
import org.eclipse.jgit.transport.sshd.DefaultProxyDataFactory;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.util.FS;

/** SSH session factory for outbound replication connections. */
@Singleton
class ReplicationSshSessionFactory extends SshdSessionFactory implements LifecycleListener {
  private final ReplicationConfig replicationConfig;

  @Inject
  ReplicationSshSessionFactory(ReplicationConfig replicationConfig) {
    super(new JGitKeyCache(), new DefaultProxyDataFactory());
    this.replicationConfig = replicationConfig;
    setHomeDirectory(FS.DETECTED.userHome());
  }

  @Override
  protected void configureClient(SshClient client) {
    replicationConfig.getSshNioWorkers().ifPresent(n -> NIO_WORKERS.set(client, n));
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    close();
  }
}
