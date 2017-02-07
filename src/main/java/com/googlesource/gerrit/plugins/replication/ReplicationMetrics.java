// Copyright (C) 2015 The Android Open Source Project
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

import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.Histogram1;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ReplicationMetrics {
  private final Timer1<String> executionTime;
  private final Histogram1<String> executionDelay;
  private final Histogram1<String> executionRetries;

  @Inject
  ReplicationMetrics(MetricMaker metricMaker) {
    Field<String> DEST_FIELD = Field.ofString("destination");

    executionTime =
        metricMaker.newTimer(
            "replication_latency",
            new Description("Time spent pushing to remote destination.")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            DEST_FIELD);

    executionDelay =
        metricMaker.newHistogram(
            "replication_delay",
            new Description("Time spent waiting before pushing to remote destination")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            DEST_FIELD);

    executionRetries =
        metricMaker.newHistogram(
            "replication_retries",
            new Description("Number of retries when pushing to remote destination")
                .setCumulative()
                .setUnit("retries"),
            DEST_FIELD);
  }

  /**
   * Start the replication latency timer for a destination.
   *
   * @param name the destination name.
   * @return the timer context.
   */
  Timer1.Context start(String name) {
    return executionTime.start(name);
  }

  /**
   * Record the replication delay and retry metrics for a destination.
   *
   * @param name the destination name.
   * @param delay replication delay in milliseconds.
   * @param retries number of retries.
   */
  void record(String name, long delay, long retries) {
    executionDelay.record(name, delay);
    executionRetries.record(name, retries);
  }
}
