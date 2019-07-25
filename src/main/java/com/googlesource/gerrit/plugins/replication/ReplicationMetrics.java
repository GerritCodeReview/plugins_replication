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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.Histogram1;
import com.google.gerrit.metrics.Histogram3;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ReplicationMetrics {
  private final Timer1<String> executionTime;
  private final Histogram1<String> executionDelay;
  private final Histogram1<String> executionRetries;
  private final Histogram3<Integer, String, String> slowProjectReplicationLatency;

  @Inject
  ReplicationMetrics(@PluginName String pluginName, MetricMaker metricMaker) {
    Field<String> DEST_FIELD =
        Field.ofString(
                "destination",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("destination", fieldValue)))
            .build();

    Field<String> PROJECT_FIELD =
        Field.ofString(
                "project",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("project", fieldValue)))
            .build();

    Field<Integer> SLOW_THRESHOLD_FIELD =
        Field.ofInteger(
                "slow_threshold",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(
                            PluginMetadata.create("slow_threshold", fieldValue.toString())))
            .build();

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

    slowProjectReplicationLatency =
        metricMaker.newHistogram(
            "latency_slower_than_threshold" + "",
            new Description(
                    "latency for project to destination, where latency was slower than threshold")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            SLOW_THRESHOLD_FIELD,
            PROJECT_FIELD,
            DEST_FIELD);
  }

  /**
   * Start the replication latency timer for a destination.
   *
   * @param name the destination name.
   * @return the timer context.
   */
  Timer1.Context<String> start(String name) {
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

  /**
   * Record replication latency for project to destination, where latency was slower than threshold
   *
   * @param destinationName the destination name.
   * @param projectName the project name.
   * @param slowThreshold replication initialDelay in milliseconds.
   * @param latency number of retries.
   */
  void recordSlowProjectReplication(
      String destinationName, String projectName, Integer slowThreshold, long latency) {
    slowProjectReplicationLatency.record(slowThreshold, destinationName, projectName, latency);
  }
}
