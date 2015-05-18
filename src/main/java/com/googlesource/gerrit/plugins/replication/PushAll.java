// Copyright (C) 2009 The Android Open Source Project
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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class PushAll implements Runnable {
  private final ReplicationStateListener stateLog;

  interface Factory {
    PushAll create(String urlMatch,
        ReplicationFilter filter,
        ReplicationState state);
  }

  private final WorkQueue workQueue;
  private final ProjectCache projectCache;
  private final ReplicationQueue replication;
  private final String urlMatch;
  private final ReplicationFilter filter;
  private final ReplicationState state;

  @Inject
  PushAll(WorkQueue wq,
      ProjectCache projectCache,
      ReplicationQueue rq,
      ReplicationStateListener stateLog,
      @Assisted @Nullable String urlMatch,
      @Assisted ReplicationFilter filter,
      @Assisted ReplicationState state) {
    this.workQueue = wq;
    this.projectCache = projectCache;
    this.replication = rq;
    this.stateLog = stateLog;
    this.urlMatch = urlMatch;
    this.filter = filter;
    this.state = state;
  }

  Future<?> schedule(long delay, TimeUnit unit) {
    return workQueue.getDefaultQueue().schedule(this, delay, unit);
  }

  @Override
  public void run() {
    try {
      for (Project.NameKey nameKey : projectCache.all()) {
        if (filter.matches(nameKey)) {
          replication.scheduleFullSync(nameKey, urlMatch, state);
        }
      }
    } catch (Exception e) {
      stateLog.error("Cannot enumerate known projects", e, state);
    }
    state.markAllPushTasksScheduled();
  }

  @Override
  public String toString() {
    String s = "Replicate All Projects";
    if (urlMatch != null) {
      s = s + " to " + urlMatch;
    }
    return s;
  }
}
