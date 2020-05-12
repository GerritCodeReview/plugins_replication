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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ForwardingIterator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

public class ChainedSchedulerTest {
  /** A simple {@link Runnable} that waits until the start() method is called. */
  public class WaitingRunnable implements Runnable {
    protected final CountDownLatch start;

    public WaitingRunnable() {
      this(new CountDownLatch(1));
    }

    public WaitingRunnable(CountDownLatch latch) {
      this.start = latch;
    }

    @Override
    public void run() {
      try {
        start.await();
      } catch (InterruptedException e) {
        missedAwaits.incrementAndGet();
      }
    }

    public void start() {
      start.countDown();
    }
  }

  /** A simple {@link Runnable} that can be awaited to start to run. */
  public static class WaitableRunnable implements Runnable {
    protected final CountDownLatch started = new CountDownLatch(1);

    @Override
    public void run() {
      started.countDown();
    }

    public boolean isStarted() {
      return started.getCount() == 0;
    }

    public boolean awaitStart(int itemsBefore) {
      try {
        return started.await(SECONDS_SYNCHRONIZE * itemsBefore, SECONDS);
      } catch (InterruptedException e) {
        return false;
      }
    }
  }

  /** An {@link Iterator} wrapper which keeps track of how many times next() has been called. */
  public static class CountingIterator extends ForwardingIterator<String> {
    public volatile int count = 0;

    protected Iterator<String> delegate;

    public CountingIterator(Iterator<String> delegate) {
      this.delegate = delegate;
    }

    @Override
    public synchronized String next() {
      count++;
      return super.next();
    }

    @Override
    protected Iterator<String> delegate() {
      return delegate;
    }
  }

  /** A {@link ChainedScheduler.Runner} which keeps track of completion and counts. */
  public static class TestRunner implements ChainedScheduler.Runner<String> {
    protected final AtomicInteger runCount = new AtomicInteger(0);
    protected final CountDownLatch onDone = new CountDownLatch(1);

    @Override
    public void run(String item) {
      incrementAndGet();
    }

    public int runCount() {
      return runCount.get();
    }

    @Override
    public void onDone() {
      onDone.countDown();
    }

    public boolean isDone() {
      return onDone.getCount() <= 0;
    }

    public boolean awaitDone(int items) {
      try {
        return onDone.await(items * SECONDS_SYNCHRONIZE, SECONDS);
      } catch (InterruptedException e) {
        return false;
      }
    }

    protected int incrementAndGet() {
      return runCount.incrementAndGet();
    }
  }

  /**
   * A {@link TestRunner} that can be awaited to start to run and additionally will wait until
   * increment() or runOnewRandomStarted() is called to complete.
   */
  public class WaitingRunner extends TestRunner {
    protected class RunContext extends WaitableRunnable {
      CountDownLatch run = new CountDownLatch(1);
      CountDownLatch ran = new CountDownLatch(1);
      int count;

      @Override
      public void run() {
        super.run();
        try {
          run.await();
          count = incrementAndGet();
          ran.countDown();
        } catch (InterruptedException e) {
          missedAwaits.incrementAndGet();
        }
      }

      public synchronized boolean startIfNotRunning() throws InterruptedException {
        if (run.getCount() > 0) {
          increment();
          return true;
        }
        return false;
      }

      public synchronized int increment() throws InterruptedException {
        run.countDown();
        ran.await(); // no timeout needed as RunContext.run() calls countDown unless interrupted
        return count;
      }
    }

    protected final Map<String, RunContext> ctxByItem = new ConcurrentHashMap<>();

    @Override
    public void run(String item) {
      context(item).run();
    }

    public void runOneRandomStarted() throws InterruptedException {
      while (true) {
        for (RunContext ctx : ctxByItem.values()) {
          if (ctx.isStarted()) {
            if (ctx.startIfNotRunning()) {
              return;
            }
          }
        }
        MILLISECONDS.sleep(1);
      }
    }

    public boolean awaitStart(String item, int itemsBefore) {
      return context(item).awaitStart(itemsBefore);
    }

    public int increment(String item) throws InterruptedException {
      return increment(item, 1);
    }

    public int increment(String item, int itemsBefore) throws InterruptedException {
      awaitStart(item, itemsBefore);
      return context(item).increment();
    }

    protected RunContext context(String item) {
      return ctxByItem.computeIfAbsent(item, k -> new RunContext());
    }
  }

  // Time for one synchronization event such as await(), or variable
  // incrementing across threads to take
  public static final int SECONDS_SYNCHRONIZE = 3;
  public static final int MANY_ITEMS_SIZE = 1000;

  public static String FIRST = item(1);
  public static String SECOND = item(2);
  public static String THIRD = item(3);

  public final AtomicInteger missedAwaits = new AtomicInteger(0); // non-zero signals an error

  @Before
  public void setup() {
    missedAwaits.set(0);
  }

  @Test
  public void emptyCompletesImmediately() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    TestRunner runner = new TestRunner();
    List<String> items = new ArrayList<>();

    new ChainedScheduler(executor, items.iterator(), runner);
    assertThat(runner.awaitDone(1)).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(0);
  }

  @Test
  public void oneItemCompletes() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    TestRunner runner = new TestRunner();
    List<String> items = new ArrayList<>();
    items.add(FIRST);

    new ChainedScheduler(executor, items.iterator(), runner);
    assertThat(runner.awaitDone(1)).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(1);
  }

  @Test
  public void manyItemsAllComplete() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    TestRunner runner = new TestRunner();
    List<String> items = createManyItems();

    new ChainedScheduler(executor, items.iterator(), runner);
    assertThat(runner.awaitDone(items.size())).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
  }

  @Test
  public void exceptionInTaskDoesNotAbortIteration() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    TestRunner runner =
        new TestRunner() {
          @Override
          public void run(String item) {
            super.run(item);
            throw new RuntimeException();
          }
        };
    List<String> items = new ArrayList<>();
    items.add(FIRST);
    items.add(SECOND);
    items.add(THIRD);

    new ChainedScheduler(executor, items.iterator(), runner);
    assertThat(runner.awaitDone(items.size())).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
  }

  @Test
  public void onDoneNotCalledBeforeAllCompleted() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    WaitingRunner runner = new WaitingRunner();
    List<String> items = createManyItems();

    new ChainedScheduler(executor, items.iterator(), runner);
    for (int i = 1; i <= items.size(); i++) {
      assertThat(runner.isDone()).isEqualTo(false);
      runner.runOneRandomStarted();
    }

    assertThat(runner.awaitDone(items.size())).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
    assertThat(missedAwaits.get()).isEqualTo(0);
  }

  @Test
  public void otherTasksOnlyEverWaitForAtMostOneRunningPlusOneWaiting() throws Exception {
    for (int threads = 1; threads <= 10; threads++) {
      ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
      WaitingRunner runner = new WaitingRunner();
      List<String> items = createItems(threads + 1 /* Running */ + 1 /* Waiting */);
      CountingIterator it = new CountingIterator(items.iterator());

      new ChainedScheduler(executor, it, runner);
      assertThat(runner.awaitStart(FIRST, 1)).isEqualTo(true); // Confirms at least one Running
      assertThat(it.count).isGreaterThan(1); // Confirms at least one extra Waiting or Running
      assertThat(it.count).isLessThan(items.size()); // Confirms at least one still not queued

      WaitableRunnable external = new WaitableRunnable();
      executor.execute(external);
      assertThat(external.isStarted()).isEqualTo(false);

      // Completes 2, (at most one Running + 1 Waiting)
      assertThat(runner.increment(FIRST)).isEqualTo(1); // Was Running
      assertThat(runner.increment(SECOND)).isEqualTo(2); // Was Waiting
      // Asserts that the one that still needed to be queued is not blocking this external task
      assertThat(external.awaitStart(1)).isEqualTo(true);

      for (int i = 3; i <= items.size(); i++) {
        runner.increment(item(i));
      }
      assertThat(missedAwaits.get()).isEqualTo(0);
    }
  }

  @Test
  public void saturatesManyFreeThreads() throws Exception {
    int threads = 10;
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads);
    WaitingRunner runner = new WaitingRunner();
    List<String> items = createManyItems();

    new ChainedScheduler(executor, items.iterator(), runner);

    for (int j = 1; j <= MANY_ITEMS_SIZE; j += threads) {
      // If #threads items can start before any complete, it proves #threads are
      // running in parallel and saturating all available threads.
      for (int i = j; i < j + threads; i++) {
        assertThat(runner.awaitStart(item(i), threads)).isEqualTo(true);
      }
      for (int i = j; i < j + threads; i++) {
        assertThat(runner.increment(item(i))).isEqualTo(i);
      }
    }

    assertThat(runner.awaitDone(threads)).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
    assertThat(missedAwaits.get()).isEqualTo(0);
  }

  @Test
  public void makesProgressEvenWhenSaturatedByOtherTasks() throws Exception {
    int blockSize = 5; // how many batches to queue at once
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(blockSize);
    List<String> items = createManyItems();
    WaitingRunner runner = new WaitingRunner();

    int batchSize = 5; // how many tasks are started concurrently
    Queue<CountDownLatch> batches = new LinkedList<>();
    for (int b = 0; b < blockSize; b++) {
      batches.add(executeWaitingRunnableBatch(batchSize, executor));
    }

    new ChainedScheduler(executor, items.iterator(), runner);

    for (int i = 1; i <= items.size(); i++) {
      for (int b = 0; b < blockSize; b++) {
        // Ensure saturation by always having at least a full thread count of
        // other tasks waiting in the queue after the waiting item so that when
        // one batch is executed, and the item then executes, there will still
        // be at least a full batch waiting.
        batches.add(executeWaitingRunnableBatch(batchSize, executor));
        batches.remove().countDown();
      }
      assertThat(runner.increment(item(i), batchSize)).isEqualTo(i); // Assert progress can be made
    }
    assertThat(runner.runCount()).isEqualTo(items.size());

    while (batches.size() > 0) {
      batches.remove().countDown();
    }
    assertThat(missedAwaits.get()).isEqualTo(0);
  }

  @Test
  public void forwardingRunnerForwards() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    TestRunner runner = new TestRunner();
    List<String> items = createManyItems();

    new ChainedScheduler(executor, items.iterator(), new ChainedScheduler.ForwardingRunner(runner));
    assertThat(runner.awaitDone(items.size())).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
  }

  @Test
  public void streamSchedulerClosesStream() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    WaitingRunner runner = new WaitingRunner();
    List<String> items = new ArrayList<>();
    items.add(FIRST);
    items.add(SECOND);

    final AtomicBoolean closed = new AtomicBoolean(false);
    Object closeRecorder =
        new Object() {
          public void close() {
            closed.set(true);
          }
        };
    Stream<String> stream =
        ForwardingProxy.create(
            Stream.class,
            items.stream(),
            new Object() {
              public void close() {
                closed.set(true);
              }
            });

    new ChainedScheduler.StreamScheduler(executor, stream, runner);
    assertThat(closed.get()).isEqualTo(false);

    // Since there is only a single thread, the Stream cannot get closed before this runs
    runner.increment(FIRST);
    // The Stream should get closed as the last item (SECOND) runs, before its runner is called
    runner.increment(SECOND); // Ensure the last item's runner has already been called
    assertThat(runner.awaitDone(items.size())).isEqualTo(true);
    assertThat(runner.runCount()).isEqualTo(items.size());
    assertThat(closed.get()).isEqualTo(true);
  }

  protected CountDownLatch executeWaitingRunnableBatch(
      int batchSize, ScheduledThreadPoolExecutor executor) {
    CountDownLatch latch = new CountDownLatch(1);
    for (int e = 0; e < batchSize; e++) {
      executor.execute(new WaitingRunnable(latch));
    }
    return latch;
  }

  protected static List<String> createManyItems() {
    return createItems(MANY_ITEMS_SIZE);
  }

  protected static List<String> createItems(int count) {
    List<String> items = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      items.add(item(i));
    }
    return items;
  }

  protected static String item(int i) {
    return "Item #" + i;
  }
}
