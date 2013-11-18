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

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitUpdateProcessing extends PushResultProcessing {
  static final Logger log = LoggerFactory.getLogger(GitUpdateProcessing.class);

  private final ChangeHooks hooks;
  private final SchemaFactory<ReviewDb> schema;

  public GitUpdateProcessing(ChangeHooks hooks, SchemaFactory<ReviewDb> schema) {
    this.hooks = hooks;
    this.schema = schema;
  }

  @Override
  void onRefReplicatedToOneNode(String project, String ref, URIish uri,
      RefPushResult status) {
    RefReplicatedEvent event =
        new RefReplicatedEvent(project, ref, resolveNodeName(uri), status);
    postEvent(project, ref, event);
  }

  @Override
  void onRefReplicatedToAllNodes(String project, String ref, int nodesCount) {
    RefReplicationDoneEvent event =
        new RefReplicationDoneEvent(project, ref, nodesCount);
    postEvent(project, ref, event);
  }

  @Override
  void onAllRefsReplicatedToAllNodes(int totalPushTasksCount) {
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
    } else {
      Branch.NameKey branch = new Branch.NameKey(Project.NameKey.parse(project), ref);
      hooks.postEvent(branch, event);
    }
  }

  private Change retrieveChange(String ref, ReviewDb db)
      throws OrmException, NoSuchChangeException {
    PatchSet.Id id = PatchSet.Id.fromRef(ref);
    Change change = db.changes().get(id.getParentKey());
    if (change == null) {
      throw new NoSuchChangeException(id.getParentKey());
    }
    return change;
  }
}