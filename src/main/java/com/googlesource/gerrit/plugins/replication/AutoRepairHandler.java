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

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoRepairHandler {
  static final String MISSING_NECESSARY_OBJECTS = "missing necessary objects";

  private final AutoRepairTracker tracker;
  private final ProjectRepairer projectRepairer;
  private final ScheduledExecutorService executor;

  @Inject
  AutoRepairHandler(
      AutoRepairTracker tracker,
      ProjectRepairer projectRepairer,
      WorkQueue workQueue,
      ReplicationConfig replicationConfig,
      @PluginName String pluginName) {
    this.tracker = tracker;
    this.projectRepairer = projectRepairer;
    this.executor =
        workQueue.createQueue(
            replicationConfig.getAutoRepairConcurrencyLimit(), pluginName + "_auto-repair");
  }

  public static boolean isMissingNecessaryObjectsError(String message) {
    return message != null && message.contains(MISSING_NECESSARY_OBJECTS);
  }

  public void handle(Project.NameKey project, URIish uri) {
    if (!tracker.tryBeginRepair(project, uri)) {
      return;
    }
    repLog.atInfo().log("Scheduling auto-repair for %s to %s", project.get(), uri);
    @SuppressWarnings("unused")
    Future<?> possiblyIgnoredError = executor.submit(new AutoRepairTask(project, uri));
  }

  private class AutoRepairTask implements Runnable {
    private final Project.NameKey project;
    private final URIish uri;

    AutoRepairTask(Project.NameKey project, URIish uri) {
      this.project = project;
      this.uri = uri;
    }

    @Override
    public void run() {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      projectRepairer.repairAndScheduleReplication(project, uri, buf);
      String output = buf.toString(StandardCharsets.UTF_8);
      if (!output.isEmpty()) {
        repLog.atInfo().log("Auto-repair output for %s:%n%s", project.get(), output);
      }
    }

    @Override
    public String toString() {
      return "auto-repair " + project.get() + " to " + uri;
    }
  }
}
