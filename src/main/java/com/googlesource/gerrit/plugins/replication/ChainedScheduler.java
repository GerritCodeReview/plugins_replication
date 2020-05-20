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

import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Non-greedily schedules consecutive tasks in a executor, one for each item returned by an {@link
 * Iterator}.
 *
 * <p>This scheduler is useful when an {@link Iterator} might provide a large amount of items to be
 * worked on in a non prioritized fashion. This scheduler will scavenge any unused threads in its
 * executor to do work, however only one item will ever be outstanding and waiting at a time. This
 * scheduling policy allows large collections to be processed without interferring much with higher
 * prioritized tasks while still making regular progress on the items provided by the {@link
 * Iterator}. To keep the level of interferrence to a minimum, ensure that the amount of work needed
 * for each item is small and short.
 */
public class ChainedScheduler<T> {
  /** Override to implement one task for one item returned by the {@link Iterator} */
  public interface Runner<T> {
    /** Will get exectuted in the threadpool task for each item */
    default void run(T item) {}

    /** Will get called after the last item completes */
    default void onDone() {}

    /** Will get called to display {@link Runnable} for item in show-queue output */
    default String toString(T item) {
      return "Chained " + item.toString();
    }
  }

  /** Override to decorate an existing {@link Runner} */
  public static class DecoratorRunner<T> implements Runner<T> {
    protected Runner<T> decoratedRunner;

    public DecoratorRunner(Runner<T> decorated) {
      decoratedRunner = decorated;
    }

    @Override
    public void run(T item) {
      decoratedRunner.run(item);
    }

    @Override
    public void onDone() {
      decoratedRunner.onDone();
    }

    @Override
    public String toString(T item) {
      return decoratedRunner.toString(item);
    }
  }

  protected class Chainer implements Runnable {
    protected T item;

    public Chainer(T item) {
      this.item = item;
    }

    @Override
    public void run() {
      next();
      try {
        runner.run(item);
      } catch (RuntimeException e) { // ignore to prevent chain from breaking
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
  protected final boolean isRemove;

  public ChainedScheduler(
      ScheduledExecutorService threadPool,
      Iterator<T> iterator,
      Runner<T> runner,
      boolean isRemove) {
    this.threadPool = threadPool;
    this.iterator = iterator;
    this.runner = runner;
    this.isRemove = isRemove;
    next();
  }

  protected void next() {
    if (!iterator.hasNext()) {
      runner.onDone();
      return;
    }

    T item = iterator.next();
    if (isRemove) {
      iterator.remove();
    }
    schedule(new Chainer(item));
  }

  protected void schedule(Runnable r) {
    threadPool.execute(r);
  }
}
