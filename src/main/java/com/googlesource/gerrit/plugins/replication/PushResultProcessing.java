// Copyright (C) 2013 The Android Open Source Project
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

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class PushResultProcessing {
  abstract void onOneNodeReplicated(String project, String ref, URIish uri, RefPushResult status);

  abstract void onAllNodesReplicated(int totalPushTasksCount);

  void writeStdOut(final String message) {
    // Default doing nothing
  }

  void writeStdErr(final String message) {
    // Default doing nothing
  }

  public static class CommandProcessing extends PushResultProcessing {
    private WeakReference<StartCommand> sshCommand;
    private AtomicBoolean hasError = new AtomicBoolean();

    CommandProcessing(StartCommand sshCommand) {
      this.sshCommand = new WeakReference<StartCommand>(sshCommand);
    }

    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
      StringBuilder sb = new StringBuilder();
      sb.append("Replicate ");
      sb.append(project);
      sb.append(" to ");
      if (uri.getHost() != null) {
        sb.append(uri.getHost());
      } else {
        sb.append("localhost");
      }
      sb.append(", ");
      switch (status) {
        case SUCCEEDED:
          sb.append("Succeeded!");
          break;
        case FAILED:
          sb.append("FAILED!");
          hasError.compareAndSet(false, true);
          break;
        case NOT_ATTEMPTED:
          sb.append("NOT ATTEMPTED!");
          break;
        default:
          sb.append("UNKNOWN RESULT!");
          break;
      }
      writeStdOut(sb.toString());
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
      if (totalPushTasksCount == 0) {
        return;
      }
      writeStdOut("----------------------------------------------");
      if (hasError.get()) {
        writeStdOut("Replication completed with some errors!");
      } else {
        writeStdOut("Replication completed successfully!");
      }
    }

    @Override
    void writeStdOut(final String message) {
      StartCommand command = sshCommand.get();
      if (command != null) {
        command.writeStdOutSync(message);
      }
    }

    @Override
    void writeStdErr(final String message) {
      StartCommand command = sshCommand.get();
      if (command != null) {
        command.writeStdErrSync(message);
      }
    }
  }

  public static class GitUpdateProcessing extends PushResultProcessing {
    static final Logger log = LoggerFactory.getLogger(GitUpdateProcessing.class);

    private final ChangeHooks hooks;
    private final SchemaFactory<ReviewDb> schema;
    private final Map<String, ReplicationData> replications =
        new HashMap<String, ReplicationData>();
    private String replicatedProject;

    @Inject
    public GitUpdateProcessing(ChangeHooks hooks, SchemaFactory<ReviewDb> schema) {
      this.hooks = hooks;
      this.schema = schema;
    }

    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
      updateReplicationInfo(project, ref);
      if (ref.startsWith("refs/changes/")) {
        ReplicationData rd = setChangeInfo(ref);
        if (rd.change != null) {
          PatchSetReplicatedEvent event =
              new PatchSetReplicatedEvent(replicatedProject, ref, uri, status);
          postEvent(rd.change, event);
        }
      }
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
      if (totalPushTasksCount == 0) {
        return;
      }
      for (Map.Entry<String, ReplicationData> e : replications.entrySet()) {
        ReplicationData rd = e.getValue();
        if (rd.change != null) {
          PatchSetReplicationDoneEvent event =
              new PatchSetReplicationDoneEvent(replicatedProject, e.getKey(),
              rd.count);
          postEvent(rd.change, event);
        }
      }
    }

    private synchronized void updateReplicationInfo(String project, String ref) {
      if (replicatedProject == null) {
        replicatedProject = project;
      }

      ReplicationData rd = replications.get(ref);
      if (rd == null) {
        rd = new ReplicationData();
        replications.put(ref, rd);
      }
      rd.count ++;

    }

    private synchronized ReplicationData setChangeInfo(String ref) {
      ReplicationData rd = replications.get(ref);
      if (rd.change != null) {
        return rd;
      }

      PatchSet.Id id;
      try {
        id = PatchSet.Id.fromRef(ref);
      } catch (IllegalArgumentException e) {
        return rd;
      }

      ReviewDb db;
      try {
        db = schema.open();
      } catch (OrmException e) {
        log.error("Cannot open database", e);
        return rd;
      }
      try {
        Change c = db.changes().get(id.getParentKey());
        //changes.put(id, c);
        rd.change = c;
      } catch (OrmException e) {
        log.error("Cannot get change from database", e);
      } finally {
        db.close();
      }
      return rd;
    }

    private void postEvent(Change c, ChangeEvent event) {
      ReviewDb db;
      try {
        db = schema.open();
      } catch (OrmException e) {
        log.error("Cannot open database for post event", e);
        return;
      }
      try {
        hooks.postEvent(c, event, db);
      } catch (OrmException e) {
        log.error("Cannot send event for a database error", e);
      } finally {
        db.close();
      }
    }

    private static class ReplicationData {
      int count;
      Change change;
    }
  }

  public static class NoopProcessing extends PushResultProcessing {
    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
    }
  }
}
