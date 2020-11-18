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

import com.google.common.flogger.FluentLogger;
import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

/**
 * Non-greedily schedules consecutive tasks in a executor, one for each item returned by an {@link
 * Iterator}.
 *
 * <p>This scheduler is useful when an {@link Iterator} might provide a large amount of items to be
 * worked on in a non prioritized fashion. This scheduler will scavenge any unused threads in its
 * executor to do work, however only one item will ever be outstanding and waiting at a time. This
 * scheduling policy allows large collections to be processed without interfering much with higher
 * prioritized tasks while still making regular progress on the items provided by the {@link
 * Iterator}. To keep the level of interference to a minimum, ensure that the amount of work needed
 * for each item is small and short.
 */
public class ChainedScheduler<T> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Override to implement a common thread pool task for all the items returned by the {@link
   * Iterator}
   */
  public interface Runner<T> {
    /** Will get executed in the thread pool task for each item */
    void run(T item);

    /** Will get called after the last item completes */
    default void onDone() {}

    /** Will get called to display {@link Runnable} for item in show-queue output */
    default String toString(T item) {
      return "Chained " + item.toString();
    }
  }

  /** Override to decorate an existing {@link Runner} */
  public static class ForwardingRunner<T> implements Runner<T> {
    protected Runner<T> delegateRunner;

    public ForwardingRunner(Runner<T> delegate) {
      delegateRunner = delegate;
    }

    @Override
    public void run(T item) {
      delegateRunner.run(item);
    }

    @Override
    public void onDone() {
      delegateRunner.onDone();
    }

    @Override
    public String toString(T item) {
      return delegateRunner.toString(item);
    }
  }

  /**
   * Use when a {@link Stream} is needed instead of an {@link Iterator}, it will close the {@link
   * Stream}
   */
  public static class StreamScheduler<T> extends ChainedScheduler<T> {
    public StreamScheduler(
        ScheduledExecutorService threadPool, final Stream<T> stream, Runner<T> runner) {
      super(
          threadPool,
          stream.iterator(),
          new ForwardingRunner<T>(runner) {
            @Override
            public void onDone() {
              stream.close();
              super.onDone();
            }
          });
    }
  }

  /** Internal {@link Runnable} containing one item to run and which schedules the next one. */
  protected class Chainer implements Runnable {
    protected T item;

    public Chainer(T item) {
      this.item = item;
    }

    @Override
    public void run() {
      boolean scheduledNext = scheduleNext();
      try {
        runner.run(item);
      } catch (RuntimeException e) { // catch to prevent chain from breaking
        logger.atSevere().withCause(e).log("Error while running: " + item);
      }
      if (!scheduledNext) {
        runner.onDone();
      }
    }

    @Override
    public String toString() {
      return runner.toString(item);
    }
  }

  protected final ScheduledExecutorService threadPool;
  protected final Iterator<T> iterator;
  protected final Runner<T> runner;

  /**
   * Note: The {@link Iterator} passed in will only ever be accessed from one thread at a time, and
   * the internal state of the {@link Iterator} will be updated after each next() call before
   * operating on the iterator again.
   */
  public ChainedScheduler(
      ScheduledExecutorService threadPool, Iterator<T> iterator, Runner<T> runner) {
    this.threadPool = threadPool;
    this.iterator = iterator;
    this.runner = runner;

    if (!scheduleNext()) {
      runner.onDone();
    }
  }

  /**
   * Concurrency note:
   *
   * <p>Since there is only one chain of tasks and each task submits the next task to the executor,
   * the calls from here to the iterator.next() call will never be executed concurrently by more
   * than one thread.
   *
   * <p>Data synchronization note:
   *
   * <p>This section in the [1] javadoc: "Actions in a thread prior to the submission of a Runnable
   * to an Executor happen-before its execution begins..." guarantees that since the iterator.next()
   * happens before the schedule(), and the new tasks call to hasNext() happen after the submission,
   * that the hasNext() call will see the results of the previous next() call even though it may
   * have happened on a different thread.
   *
   * <p>[1]
   * <li>https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/package-summary.html
   *     (section "Memory Consistency Properties")
   * <li>The methods of all classes in java.util.concurrent and its subpackages extends the
   *     guarantees of the java memory model to higher-level synchronization.
   * <li>In particular this guarantee of the java.util.concurrent applies here:
   */
  protected boolean scheduleNext() {
    if (!iterator.hasNext()) {
      return false;
    }

    schedule(new Chainer(iterator.next()));
    return true;
  }

  protected void schedule(Runnable r) {
    threadPool.execute(r);
  }
}
