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

import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isGerrit;
import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isSSH;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages automatic replication to remote repositories. */
public class ReplicationQueue
    implements LifecycleListener,
        GitReferenceUpdatedListener,
        NewProjectCreatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {
  static final String REPLICATION_LOG_NAME = "replication_log";
  static final Logger repLog = LoggerFactory.getLogger(REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  static String replaceName(String in, String name, boolean keyIsOptional) {
    String key = "${name}";
    int n = in.indexOf(key);
    if (0 <= n) {
      return in.substring(0, n) + name + in.substring(n + key.length());
    }
    if (keyIsOptional) {
      return in;
    }
    return null;
  }

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final ReplicationConfig config;
  private final AdminApiFactory adminApiFactory;
  private final ReplicationState.Factory replicationStateFactory;
  private final EventsStorage eventsStorage;
  private volatile boolean running;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      AdminApiFactory aaf,
      ReplicationConfig rc,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListener sl,
      ReplicationState.Factory rsf,
      EventsStorage es) {
    workQueue = wq;
    dispatcher = dis;
    config = rc;
    stateLog = sl;
    adminApiFactory = aaf;
    replicationStateFactory = rsf;
    eventsStorage = es;
  }

  @Override
  public void start() {
    if (!running) {
      config.startup(workQueue);
      running = true;
      firePendingEvents();
    }
  }

  @Override
  public void stop() {
    running = false;
    int discarded = config.shutdown();
    if (discarded > 0) {
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
  }

  void scheduleFullSync(Project.NameKey project, String urlMatch, ReplicationState state) {
    scheduleFullSync(project, urlMatch, state, false);
  }

  void scheduleFullSync(
      Project.NameKey project, String urlMatch, ReplicationState state, boolean now) {
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event", state);
      return;
    }

    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          cfg.schedule(project, PushOne.ALL_REFS, uri, state, now);
        }
      }
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    onGitReferenceUpdated(event.getProjectName(), event.getRefName());
  }

  private void onGitReferenceUpdated(String projectName, String refName) {
    ReplicationState state =
        replicationStateFactory.create(new GitUpdateProcessing(dispatcher.get()));
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event", state);
      return;
    }

    Project.NameKey project = new Project.NameKey(projectName);
    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project) && cfg.wouldPushRef(refName)) {
        String eventKey = eventsStorage.persist(projectName, refName);
        state.setEventKey(eventKey);
        for (URIish uri : cfg.getURIs(project, null)) {
          cfg.schedule(project, refName, uri, state);
        }
      }
    }
    state.markAllPushTasksScheduled();
  }

  private void firePendingEvents() {
    for (EventsStorage.ReplicateRefUpdate e : eventsStorage.list()) {
      repLog.info("Firing pending event {}", e);
      onGitReferenceUpdated(e.project, e.ref);
    }
  }

  @Override
  public void onNewProjectCreated(NewProjectCreatedListener.Event event) {
    Project.NameKey projectName = new Project.NameKey(event.getProjectName());
    for (URIish uri : getURIs(projectName, FilterType.PROJECT_CREATION)) {
      createProject(uri, projectName, event.getHeadName());
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey projectName = new Project.NameKey(event.getProjectName());
    for (URIish uri : getURIs(projectName, FilterType.PROJECT_DELETION)) {
      deleteProject(uri, projectName);
    }
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey project = new Project.NameKey(event.getProjectName());
    for (URIish uri : getURIs(project, FilterType.ALL)) {
      updateHead(uri, project, event.getNewHeadName());
    }
  }

  private Set<URIish> getURIs(Project.NameKey projectName, FilterType filterType) {
    if (config.getDestinations(filterType).isEmpty()) {
      return Collections.emptySet();
    }
    if (!running) {
      repLog.error("Replication plugin did not finish startup before event");
      return Collections.emptySet();
    }

    Set<URIish> uris = new HashSet<>();
    for (Destination config : this.config.getDestinations(filterType)) {
      if (!config.wouldPushProject(projectName)) {
        continue;
      }

      boolean adminURLUsed = false;

      for (String url : config.getAdminUrls()) {
        if (Strings.isNullOrEmpty(url)) {
          continue;
        }

        URIish uri;
        try {
          uri = new URIish(url);
        } catch (URISyntaxException e) {
          repLog.warn("adminURL '{}' is invalid: {}", url, e.getMessage());
          continue;
        }

        if (!isGerrit(uri)) {
          String path =
              replaceName(uri.getPath(), projectName.get(), config.isSingleProjectMatch());
          if (path == null) {
            repLog.warn("adminURL {} does not contain ${name}", uri);
            continue;
          }

          uri = uri.setPath(path);
          if (!isSSH(uri)) {
            repLog.warn("adminURL '{}' is invalid: only SSH is supported", uri);
            continue;
          }
        }
        uris.add(uri);
        adminURLUsed = true;
      }

      if (!adminURLUsed) {
        for (URIish uri : config.getURIs(projectName, "*")) {
          uris.add(uri);
        }
      }
    }
    return uris;
  }

  public boolean createProject(Project.NameKey project, String head) {
    boolean success = true;
    for (URIish uri : getURIs(project, FilterType.PROJECT_CREATION)) {
      success &= createProject(uri, project, head);
    }
    return success;
  }

  private boolean createProject(URIish replicateURI, Project.NameKey projectName, String head) {
    Optional<AdminApi> adminApi = adminApiFactory.create(replicateURI);
    if (adminApi.isPresent()) {
      adminApi.get().createProject(projectName, head);
      return true;
    }

    warnCannotPerform("create new project", replicateURI);
    return false;
  }

  private void deleteProject(URIish replicateURI, Project.NameKey projectName) {
    Optional<AdminApi> adminApi = adminApiFactory.create(replicateURI);
    if (adminApi.isPresent()) {
      adminApi.get().deleteProject(projectName);
      return;
    }

    warnCannotPerform("delete project", replicateURI);
  }

  private void updateHead(URIish replicateURI, Project.NameKey projectName, String newHead) {
    Optional<AdminApi> adminApi = adminApiFactory.create(replicateURI);
    if (adminApi.isPresent()) {
      adminApi.get().updateHead(projectName, newHead);
      return;
    }

    warnCannotPerform("update HEAD of project", replicateURI);
  }

  private void warnCannotPerform(String op, URIish uri) {
    repLog.warn(
        "Cannot {} on remote site {}."
            + "Only local paths and SSH URLs are supported for this operation",
        op,
        uri);
  }
}
