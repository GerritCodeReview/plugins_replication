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
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
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

  private static String resolveNodeName(URIish uri) {
    StringBuilder sb = new StringBuilder();
    if (uri.isRemote()) {
      sb.append(uri.getHost());
      if (uri.getPort() != -1) {
        sb.append(":");
        sb.append(uri.getPort());
      }
    } else {
      sb.append(uri.getPath());
    }
    return sb.toString();
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
      sb.append(resolveNodeName(uri));
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
    private String replicatedProject;
    private String replicatedRef;
    private int replicationsCount;

    @Inject
    public GitUpdateProcessing(ChangeHooks hooks, SchemaFactory<ReviewDb> schema) {
      this.hooks = hooks;
      this.schema = schema;
    }

    @Override
    void onOneNodeReplicated(String project, String ref, URIish uri,
        RefPushResult status) {
      updateReplicationInfo(project, ref);
      RefReplicatedEvent event =
          new RefReplicatedEvent(project, ref, resolveNodeName(uri), status);
      postEvent(project, ref, event);
    }

    @Override
    void onAllNodesReplicated(int totalPushTasksCount) {
      if (totalPushTasksCount == 0) {
        return;
      }
      RefReplicationDoneEvent event =
          new RefReplicationDoneEvent(replicatedProject, replicatedRef,
              replicationsCount);
      postEvent(replicatedProject, replicatedRef, event);
    }

    private synchronized void updateReplicationInfo(String project, String ref) {
      if (replicatedProject == null) {
        replicatedProject = project;
      }
      if (replicatedRef == null) {
        replicatedRef = ref;
      }
      replicationsCount++;
    }

    private void postEvent(String project, String ref, ChangeEvent event) {
      if (PatchSet.isRef(ref)) {
        try {
          ReviewDb db = schema.open();
          try {
            hooks.postEvent(retrieveChange(ref, db), event, db);
          } finally {
            db.close();
          }
        } catch (Exception e) {
          log.error("Cannot post event", e);
        }
      }else{
        Branch.NameKey branch = new Branch.NameKey(Project.NameKey.parse(project), ref);
        hooks.postEvent(branch, event);
      }
    }

    private Change retrieveChange(String ref,ReviewDb db)
        throws OrmException, NoSuchChangeException {
      PatchSet.Id id = PatchSet.Id.fromRef(ref);
      Change change = db.changes().get(id.getParentKey());
      if (change == null) {
        throw new NoSuchChangeException(id.getParentKey());
      }
      return change;
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
