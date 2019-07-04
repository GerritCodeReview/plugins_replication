// Copyright (C) 2019 The Android Open Source Project
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
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;

import com.google.common.eventbus.EventBus;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;

public class AutoReloadConfigTest {
  private Destination.Factory destinationFactoryMock;
  private ReplicationQueue replicationQueueMock;
  private WorkQueue workQueueMock;
  private SitePaths sitePaths;
  private Path pluginDataPath;
  private Destination destinationMock;
  private ReplicationFileBasedConfig replicationFileBasedConfig;
  private DynamicSet<ReplicationConfigValidator> configValidators;
  private FakeExecutorService executorService = new FakeExecutorService();
  private EventBus eventBus = new EventBus();

  public class FakeExecutorService implements ScheduledExecutorService {
    public Runnable refreshCommand = () -> {};

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return null;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return false;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return null;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return null;
    }

    @Override
    public Future<?> submit(Runnable task) {
      return null;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return null;
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return null;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return null;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return null;
    }

    @Override
    public void execute(Runnable command) {}

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return null;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      refreshCommand = command;
      return null;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return null;
    }
  }

  @Before
  public void setup() throws IOException {
    pluginDataPath = createTempPath("data");
    sitePaths = new SitePaths(createTempPath("site"));
    configValidators = new DynamicSet<>();

    setupMocks();

    replicationFileBasedConfig = newReplicationFileBasedConfig();
  }

  private void setupMocks() {
    destinationMock = createNiceMock(Destination.class);
    replay(destinationMock);

    destinationFactoryMock = createMock(Destination.Factory.class);
    expect(destinationFactoryMock.create(anyObject(DestinationConfiguration.class)))
        .andReturn(destinationMock)
        .anyTimes();
    replay(destinationFactoryMock);

    replicationQueueMock = createNiceMock(ReplicationQueue.class);
    replay(replicationQueueMock);

    workQueueMock = createNiceMock(WorkQueue.class);
    expect(workQueueMock.createQueue(anyInt(), anyObject(String.class))).andReturn(executorService);
    replay(workQueueMock);
  }

  private Path createTempPath(String prefix) throws IOException {
    return java.nio.file.Files.createTempDirectory(prefix);
  }

  @Test
  public void shouldLoadNotEmptyInitialReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.somewhere.com/${name}");
    replicationConfig.save();

    assertThat(newDestinationsCollections().getAll(FilterType.ALL)).isNotEmpty();
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", true);
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.foo.com/${name}");
    replicationConfig.save();

    AutoReloadConfigDecorator autoReloadConfig = newAutoReloadConfig();
    DestinationsCollection destinations = newDestinationsCollections();

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(2);
  }

  @Test
  public void shouldNotAutoReloadReplicationConfigIfDisabled() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", false);
    replicationConfig.setString("remote", "foo", "url", "ssh://git@git.foo.com/${name}");
    replicationConfig.save();

    DestinationsCollection destinations = newDestinationsCollections();
    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(destinations.getAll(FilterType.ALL)).hasSize(1);
  }

  private AutoReloadConfigDecorator newAutoReloadConfig() {
    AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            configValidators, replicationFileBasedConfig, sitePaths, pluginDataPath, eventBus);
    AutoReloadConfigDecorator configDecorator =
        new AutoReloadConfigDecorator(
            "replication", workQueueMock, replicationFileBasedConfig, autoReloadRunnable, eventBus);
    configDecorator.start();
    return configDecorator;
  }

  private ReplicationFileBasedConfig newReplicationFileBasedConfig() {
    return new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
  }

  private FileBasedConfig newReplicationConfig() {
    FileBasedConfig replicationConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    return replicationConfig;
  }

  private DestinationsCollection newDestinationsCollections() throws ConfigInvalidException {
    DestinationsCollection destinations =
        new DestinationsCollection(
            destinationFactoryMock,
            Providers.of(replicationQueueMock),
            replicationFileBasedConfig,
            eventBus);
    destinations.startup(workQueueMock);
    return destinations;
  }
}
