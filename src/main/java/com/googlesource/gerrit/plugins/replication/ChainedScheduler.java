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
import java.util.stream.Stream;

/**
 * Non-greedily schedules consecutive tasks in a executor, one for each item returned by an
 * Iterator.
 *
 * <p>This scheduler is useful when an Iterator might provide a large amount of items to be worked
 * on in a non prioritized fashion. This scheduler will scavenge any unused threads in its executor
 * to do work, however only one item will ever be outstanding and waiting at a time. This scheduling
 * policy allows large collections to be processed without interferring much with higher prioritized
 * tasks while still making regular progress on the items provided by the Iterator. To keep the
 * level of interferrence to a minimum, ensure that the amount of work needed for each item is small
 * and short.
 */
public class ChainedScheduler<T> {
  /** Override to implement one task for one item returned by the Iterator. */
  public interface Runner<T> {
    /** will get exectuted for each item */
    default void run(T item) {}
    /** will get executed after the last item completes */
    default void onDone() {}
  }
  /** Use when a Stream is needed instead of an Iterator, it will close the Stream. */
  public static class StreamScheduler<T> extends ChainedScheduler<T> {
    public StreamScheduler(
        ScheduledExecutorService threadPool, final Stream<T> stream, final Runner<T> runner) {
      super(
          threadPool,
          stream.iterator(),
          new Runner<T>() {
            @Override
            public void run(T item) {
              runner.run(item);
            }

            @Override
            public void onDone() {
              stream.close();
              runner.onDone();
            }
          });
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
  }

  protected final ScheduledExecutorService threadPool;
  protected final Iterator<T> iterator;
  protected final Runner<T> runner;

  public ChainedScheduler(
      ScheduledExecutorService threadPool, Iterator<T> iterator, Runner<T> runner) {
    this.threadPool = threadPool;
    this.iterator = iterator;
    this.runner = runner;
    next();
  }

  public synchronized void next() {
    if (!iterator.hasNext()) {
      runner.onDone();
      return;
    }
    T item = iterator.next();
    schedule(new Chainer(item));
  }

  protected void schedule(Runnable r) {
    threadPool.execute(r);
  }
}
