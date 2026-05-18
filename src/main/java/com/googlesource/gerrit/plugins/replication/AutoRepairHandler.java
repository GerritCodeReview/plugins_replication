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
import com.googlesource.gerrit.plugins.replication.events.dispatcher.EventDispatcher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class AutoRepairHandler {
  static final String MISSING_NECESSARY_OBJECTS = "missing necessary objects";

  private final AutoRepairTracker tracker;
  private final ProjectRepairer projectRepairer;
  private final ReplicationStarter replicationStarter;
  private final EventDispatcher eventDispatcher;
  private final ScheduledExecutorService executor;

  @Inject
  AutoRepairHandler(
      AutoRepairTracker tracker,
      ProjectRepairer projectRepairer,
      ReplicationStarter replicationStarter,
      EventDispatcher eventDispatcher,
      WorkQueue workQueue,
      ReplicationConfig replicationConfig,
      @PluginName String pluginName) {
    this.tracker = tracker;
    this.projectRepairer = projectRepairer;
    this.replicationStarter = replicationStarter;
    this.eventDispatcher = eventDispatcher;
    this.executor =
        workQueue.createQueue(
            replicationConfig.getAutoRepairConcurrencyLimit(), pluginName + "_auto-repair");
  }

  public static boolean isMissingNecessaryObjectsError(String message) {
    return message != null && message.contains(MISSING_NECESSARY_OBJECTS);
  }

  public void handle(
      Project.NameKey project,
      URIish uri,
      String remoteName,
      UrlDistributionStrategy urlDistributionStrategy) {
    if (!tracker.tryBeginRepair(project, uri, remoteName, urlDistributionStrategy)) {
      return;
    }
    repLog.atInfo().log("Scheduling auto-repair for project %s to %s", project.get(), uri);
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
      boolean isRepaired = projectRepairer.repair(project, uri, buf, true);
      (isRepaired ? repLog.atInfo() : repLog.atWarning())
          .log(
              "Auto-repair %s for project %s to %s:%s",
              isRepaired ? "succeeded" : "failed",
              project.get(),
              uri,
              buf.toString(StandardCharsets.UTF_8));
      if (isRepaired) {
        replicationStarter.start(
            uri.toString(),
            Set.of(),
            new ReplicationFilter(List.of(project.get()), Collections.emptyList()),
            /* now= */ true,
            /* wait= */ false,
            new PushResultProcessing.GitUpdateProcessing(eventDispatcher));
        repLog.atInfo().log("Scheduled full replication of %s to %s", project.get(), uri);
      }
    }

    @Override
    public String toString() {
      return "auto-repair " + project.get() + " to " + uri;
    }
  }
}
