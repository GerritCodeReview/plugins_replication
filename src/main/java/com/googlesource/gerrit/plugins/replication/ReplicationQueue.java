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

import com.google.common.base.Strings;
import com.google.common.collect.Queues;
import com.google.gerrit.common.EventDispatcher;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.QuotedString;
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
  private final SshHelper sshHelper;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final ReplicationConfig config;
  private final GerritSshApi gerritAdmin;
  private volatile boolean running;
  private final Queue<GitReferenceUpdatedListener.Event> beforeStartupEventsQueue;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      SshHelper sh,
      GerritSshApi ga,
      ReplicationConfig rc,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListener sl) {
    workQueue = wq;
    sshHelper = sh;
    dispatcher = dis;
    config = rc;
    stateLog = sl;
    gerritAdmin = ga;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
  }

  @Override
  public void start() {
    config.startup(workQueue);
    running = true;
    fireBeforeStartupEvents();
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
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(event);
      return;
    }

    Project.NameKey project = new Project.NameKey(event.getProjectName());
    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project) && cfg.wouldPushRef(event.getRefName())) {
        for (URIish uri : cfg.getURIs(project, null)) {
          cfg.schedule(project, event.getRefName(), uri, state);
        }
      }
    }
    state.markAllPushTasksScheduled();
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

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (GitReferenceUpdatedListener.Event event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.getProjectName(), event.getRefName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        onGitReferenceUpdated(event);
        eventsReplayed.add(eventKey);
      }
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
    if (isGerrit(replicateURI)) {
      gerritAdmin.createProject(replicateURI, projectName, head);
    } else if (!replicateURI.isRemote()) {
      createLocally(replicateURI, head);
    } else if (isSSH(replicateURI)) {
      createRemoteSsh(replicateURI, head);
    } else {
      repLog.warn(
          "Cannot create new project on remote site {}."
              + " Only local paths and SSH URLs are supported"
              + " for remote repository creation",
          replicateURI);
      return false;
    }
    return true;
  }

  private static void createLocally(URIish uri, String head) {
    try (Repository repo = new FileRepository(uri.getPath())) {
      repo.create(true /* bare */);

      if (head != null && head.startsWith(Constants.R_REFS)) {
        RefUpdate u = repo.updateRef(Constants.HEAD);
        u.disableRefLog();
        u.link(head);
      }
      repLog.info("Created local repository: {}", uri);
    } catch (IOException e) {
      repLog.error("Error creating local repository {}:\n", uri.getPath(), e);
    }
  }

  private void createRemoteSsh(URIish uri, String head) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "mkdir -p " + quotedPath + " && cd " + quotedPath + " && git init --bare";
    if (head != null) {
      cmd = cmd + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(head);
    }
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
      repLog.info("Created remote repository: {}", uri);
    } catch (IOException e) {
      repLog.error(
          "Error creating remote repository at {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          e,
          cmd,
          errStream,
          e);
    }
  }

  private void deleteProject(URIish replicateURI, Project.NameKey projectName) {
    if (isGerrit(replicateURI)) {
      gerritAdmin.deleteProject(replicateURI, projectName);
      repLog.info("Deleted remote repository: " + replicateURI);
    } else if (!replicateURI.isRemote()) {
      deleteLocally(replicateURI);
    } else if (isSSH(replicateURI)) {
      deleteRemoteSsh(replicateURI);
    } else {
      repLog.warn(
          "Cannot delete project on remote site {}. "
              + "Only local paths and SSH URLs are supported"
              + " for remote repository deletion",
          replicateURI);
    }
  }

  private static void deleteLocally(URIish uri) {
    try {
      recursivelyDelete(new File(uri.getPath()));
      repLog.info("Deleted local repository: {}", uri);
    } catch (IOException e) {
      repLog.error("Error deleting local repository {}:\n", uri.getPath(), e);
    }
  }

  private static void recursivelyDelete(File dir) throws IOException {
    File[] contents = dir.listFiles();
    if (contents != null) {
      for (File d : contents) {
        if (d.isDirectory()) {
          recursivelyDelete(d);
        } else {
          if (!d.delete()) {
            throw new IOException("Failed to delete: " + d.getAbsolutePath());
          }
        }
      }
    }
    if (!dir.delete()) {
      throw new IOException("Failed to delete: " + dir.getAbsolutePath());
    }
  }

  private void deleteRemoteSsh(URIish uri) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "rm -rf " + quotedPath;
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
      repLog.info("Deleted remote repository: {}", uri);
    } catch (IOException e) {
      repLog.error(
          "Error deleting remote repository at {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          e,
          cmd,
          errStream,
          e);
    }
  }

  private void updateHead(URIish replicateURI, Project.NameKey projectName, String newHead) {
    if (isGerrit(replicateURI)) {
      gerritAdmin.updateHead(replicateURI, projectName, newHead);
    } else if (!replicateURI.isRemote()) {
      updateHeadLocally(replicateURI, newHead);
    } else if (isSSH(replicateURI)) {
      updateHeadRemoteSsh(replicateURI, newHead);
    } else {
      repLog.warn(
          "Cannot update HEAD of project on remote site {}."
              + " Only local paths and SSH URLs are supported"
              + " for remote HEAD update.",
          replicateURI);
    }
  }

  private void updateHeadRemoteSsh(URIish uri, String newHead) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd =
        "cd " + quotedPath + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(newHead);
    OutputStream errStream = sshHelper.newErrorBufferStream();
    try {
      sshHelper.executeRemoteSsh(uri, cmd, errStream);
    } catch (IOException e) {
      repLog.error(
          "Error updating HEAD of remote repository at {} to {}:\n"
              + "  Exception: {}\n"
              + "  Command: {}\n"
              + "  Output: {}",
          uri,
          newHead,
          e,
          cmd,
          errStream,
          e);
    }
  }

  private static void updateHeadLocally(URIish uri, String newHead) {
    try (Repository repo = new FileRepository(uri.getPath())) {
      if (newHead != null) {
        RefUpdate u = repo.updateRef(Constants.HEAD);
        u.link(newHead);
      }
    } catch (IOException e) {
      repLog.error("Failed to update HEAD of repository {} to {}", uri.getPath(), newHead, e);
    }
  }

  private static boolean isSSH(URIish uri) {
    String scheme = uri.getScheme();
    if (!uri.isRemote()) {
      return false;
    }
    if (scheme != null && scheme.toLowerCase().contains("ssh")) {
      return true;
    }
    if (scheme == null && uri.getHost() != null && uri.getPath() != null) {
      return true;
    }
    return false;
  }

  private static boolean isGerrit(URIish uri) {
    String scheme = uri.getScheme();
    return scheme != null && scheme.toLowerCase().equals("gerrit+ssh");
  }
}
