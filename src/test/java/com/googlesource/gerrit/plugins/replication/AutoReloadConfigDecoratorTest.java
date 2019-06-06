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
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;

import com.google.common.eventbus.EventBus;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.IOException;
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
import org.junit.Before;
import org.junit.Test;

public class AutoReloadConfigDecoratorTest extends AbstractConfigTest {
  private AutoReloadConfigDecorator autoReloadConfig;
  private ReplicationQueue replicationQueueMock;
  private WorkQueue workQueueMock;
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

  public AutoReloadConfigDecoratorTest() throws IOException {
    super();
  }

  @Override
  @Before
  public void setup() {
    super.setup();

    setupMocks();
  }

  private void setupMocks() {
    replicationQueueMock = createNiceMock(ReplicationQueue.class);
    replay(replicationQueueMock);

    workQueueMock = createNiceMock(WorkQueue.class);
    expect(workQueueMock.createQueue(anyInt(), anyObject(String.class))).andReturn(executorService);
    replay(workQueueMock);
  }

  @Test
  public void shouldLoadNotEmptyInitialReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    String remoteName = "foo";
    String remoteUrl = "ssh://git@git.somewhere.com/${name}";
    replicationConfig.setString("remote", remoteName, "url", remoteUrl);
    replicationConfig.save();

    List<Destination> destinations = newAutoReloadConfig().getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName, remoteUrl);
  }

  @Test
  public void shouldAutoReloadReplicationConfig() throws Exception {
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", true);
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    replicationConfig.setString("remote", remoteName1, "url", remoteUrl1);
    replicationConfig.save();

    AutoReloadConfigDecorator autoReloadConfig = newAutoReloadConfig();
    autoReloadConfig.startup(workQueueMock);

    List<Destination> destinations = autoReloadConfig.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    String remoteName2 = "bar";
    String remoteUrl2 = "ssh://git@git.bar.com/${name}";
    replicationConfig.setString("remote", remoteName2, "url", remoteUrl2);
    replicationConfig.save();
    executorService.refreshCommand.run();

    destinations = autoReloadConfig.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(2);
    assertThatContainsDestination(destinations, remoteName1, remoteUrl1);
    assertThatContainsDestination(destinations, remoteName2, remoteUrl2);
  }

  @Test
  public void shouldNotAutoReloadReplicationConfigIfDisabled() throws Exception {
    String remoteName1 = "foo";
    String remoteUrl1 = "ssh://git@git.foo.com/${name}";
    FileBasedConfig replicationConfig = newReplicationConfig();
    replicationConfig.setBoolean("gerrit", null, "autoReload", false);
    replicationConfig.setString("remote", remoteName1, "url", remoteUrl1);
    replicationConfig.save();

    ReplicationFileBasedConfig replicationFileBasedConfig =
        new ReplicationFileBasedConfig(sitePaths, destinationFactoryMock, pluginDataPath);

    List<Destination> destinations = replicationFileBasedConfig.getAll(FilterType.ALL);
    assertThat(destinations).hasSize(1);
    assertThatIsDestination(destinations.get(0), remoteName1, remoteUrl1);

    TimeUnit.SECONDS.sleep(1); // Allow the filesystem to change the update TS

    replicationConfig.setString("remote", "bar", "url", "ssh://git@git.bar.com/${name}");
    replicationConfig.save();
    executorService.refreshCommand.run();

    assertThat(replicationFileBasedConfig.getAll(FilterType.ALL)).isEqualTo(destinations);
  }

  private AutoReloadConfigDecorator newAutoReloadConfig()
      throws ConfigInvalidException, IOException {
    ReplicationFileBasedConfig replicationFileBasedConfig =
        new ReplicationFileBasedConfig(sitePaths, destinationFactoryMock, pluginDataPath);
    AutoReloadRunnable autoReloadRunnable =
        new AutoReloadRunnable(
            replicationFileBasedConfig,
            sitePaths,
            destinationFactoryMock,
            pluginDataPath,
            eventBus);
    return new AutoReloadConfigDecorator(
        Providers.of(replicationQueueMock),
        "replication",
        workQueueMock,
        replicationFileBasedConfig,
        autoReloadRunnable,
        eventBus);
  }
}
