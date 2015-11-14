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
  Timer1<String> executionTime;
  Histogram1<String> executionDelay;
  Histogram1<String> executionRetries;

  @Inject
  ReplicationMetrics(MetricMaker metricMaker) {
    Field<String> F_NAME = Field.ofString("destination");

    executionTime = metricMaker.newTimer(
        "replication_latency",
        new Description("Time spent pushing to remote destination.")
          .setCumulative()
          .setUnit(Description.Units.SECONDS),
        F_NAME);

    executionDelay = metricMaker.newHistogram(
        "replication_delay",
        new Description("Time spent waiting before pushing to remote destination")
          .setCumulative()
          .setUnit(Description.Units.SECONDS),
        F_NAME);

    executionRetries = metricMaker.newHistogram(
        "replication_retries",
        new Description("Number of retries when pushing to remote destination")
          .setCumulative()
          .setUnit("retries"),
        F_NAME);
  }

  Timer1.Context start(String name) {
    return executionTime.start(name);
  }

  void record(String destination, long delay, long retries) {
    executionDelay.record(destination, delay);
    executionRetries.record(destination, retries);
  }

}
