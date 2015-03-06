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
import com.google.gerrit.common.EventDispatcher;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;

import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.QuotedString;
import org.eclipse.jgit.util.io.StreamCopyThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manages automatic replication to remote repositories. */
public class ReplicationQueue implements
    LifecycleListener,
    GitReferenceUpdatedListener,
    NewProjectCreatedListener,
    ProjectDeletedListener,
    HeadUpdatedListener {
  static final String REPLICATION_LOG_NAME = "replication_log";
  static final Logger repLog = LoggerFactory.getLogger(REPLICATION_LOG_NAME);
  private static final int SSH_REMOTE_TIMEOUT = 120 * 1000;
  private static final ReplicationStateLogger stateLog =
      new ReplicationStateLogger(repLog);

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
  private final SchemaFactory<ReviewDb> database;
  private final EventDispatcher dispatcher;
  private final ReplicationConfig config;
  private final ReplicationSshSessionFactory sshSessionFactory;
  private volatile boolean running;
  private CredentialsFactory credentialsFactory;

  @Inject
  protected ReplicationQueue(
      WorkQueue wq,
      EventDispatcher dis,
      ReplicationConfig rc,
      SchemaFactory<ReviewDb> db,
      ReplicationSshSessionFactory sshSessionFactory,
      CredentialsFactory credentialsFactory) {
    workQueue = wq;
    database = db;
    dispatcher = dis;
    config = rc;
    this.sshSessionFactory = sshSessionFactory;
    this.credentialsFactory = credentialsFactory;
  }

  @Override
  public void start() {
    config.startup(workQueue);
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    int discarded = config.shutdown();
    if (discarded > 0) {
      repLog.warn(String.format(
          "Cancelled %d replication events during shutdown", discarded));
    }
  }

  void scheduleFullSync(final Project.NameKey project, final String urlMatch,
      ReplicationState state) {
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event",
          state);
      return;
    }

    for (Destination cfg : config.getDestinations(FilterType.ALL)) {
      if (cfg.wouldPushProject(project)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          cfg.schedule(project, PushOne.ALL_REFS, uri, state);
        }
      }
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher, database));
    if (!running) {
      stateLog.warn("Replication plugin did not finish startup before event", state);
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
    String projectName = event.getProjectName();
    for (Map.Entry<String, Set<URIish>> entry : getURIs(new Project.NameKey(
        projectName), FilterType.PROJECT_CREATION).entrySet()) {
      SecureCredentialsProvider credsProvider =
          credentialsFactory.create(entry.getKey());
      for (URIish uri : entry.getValue()) {
        createProject(credsProvider, uri, event.getHeadName());
      }
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    String projectName = event.getProjectName();
    for (Map.Entry<String, Set<URIish>> entry : getURIs(
        new Project.NameKey(projectName), FilterType.PROJECT_DELETION)
        .entrySet()) {
      SecureCredentialsProvider credsProvider =
          credentialsFactory.create(entry.getKey());
      for (URIish uri : entry.getValue()) {
        deleteProject(credsProvider, uri);
      }
    }
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    String projectName = event.getProjectName();
    for (Map.Entry<String, Set<URIish>> entry : getURIs(
        new Project.NameKey(projectName), FilterType.ALL).entrySet()) {
      SecureCredentialsProvider credsProvider =
          credentialsFactory.create(entry.getKey());
      for (URIish uri : entry.getValue()) {
        updateHead(credsProvider, uri, event.getNewHeadName());
      }
    }
  }

  private Map<String, Set<URIish>> getURIs(Project.NameKey projectName,
      FilterType filterType) {
    if (config.getDestinations(filterType).isEmpty()) {
      return Collections.emptyMap();
    }
    if (!running) {
      repLog.error("Replication plugin did not finish startup before event");
      return Collections.emptyMap();
    }

    Map<String, Set<URIish>> result = new HashMap<>();
    for (Destination config : this.config.getDestinations(filterType)) {
      if (!config.wouldPushProject(projectName)) {
        continue;
      }

      Set<URIish> uris = new HashSet<>();
      List<URIish> uriList = config.getURIs(projectName, "*");
      String[] adminUrls = config.getAdminUrls();
      boolean adminURLUsed = false;

      for (String url : adminUrls) {
        if (Strings.isNullOrEmpty(url)) {
          continue;
        }

        URIish uri;
        try {
          uri = new URIish(url);
        } catch (URISyntaxException e) {
          repLog.warn(String.format("adminURL '%s' is invalid: %s", url,
              e.getMessage()));
          continue;
        }

        String path = replaceName(uri.getPath(), projectName.get(),
            config.isSingleProjectMatch());
        if (path == null) {
          repLog.warn(String
              .format("adminURL %s does not contain ${name}", uri));
          continue;
        }

        uri = uri.setPath(path);
        if (!isSSH(uri)) {
          repLog.warn(String.format(
              "adminURL '%s' is invalid: only SSH is supported", uri));
          continue;
        }

        uris.add(uri);
        adminURLUsed = true;
      }

      if (!adminURLUsed) {
        for (URIish uri : uriList) {
          uris.add(uri);
        }
      }
      result.put(config.getName(), uris);
    }
    return result;
  }

  public boolean createProject(Project.NameKey project, String head) {
    boolean success = false;
    for (Map.Entry<String, Set<URIish>> entry : getURIs(project,
        FilterType.PROJECT_CREATION).entrySet()) {
      SecureCredentialsProvider credsProvider =
          credentialsFactory.create(entry.getKey());
      for (URIish uri : entry.getValue()) {
        success &= createProject(credsProvider, uri, head);
      }
    }
    return success;
  }

  private boolean createProject(CredentialsProvider credsProvider,
      URIish replicateURI, String head) {
    if (!replicateURI.isRemote()) {
      createLocally(replicateURI, head);
      repLog.info("Created local repository: " + replicateURI);
    } else if (isSSH(replicateURI)) {
      createRemoteSsh(credsProvider, replicateURI, head);
      repLog.info("Created remote repository: " + replicateURI);
    } else {
      repLog.warn(String.format("Cannot create new project on remote site %s."
          + " Only local paths and SSH URLs are supported"
          + " for remote repository creation", replicateURI));
      return false;
    }
    return true;
  }

  private static void createLocally(URIish uri, String head) {
    try {
      Repository repo = new FileRepository(uri.getPath());
      try {
        repo.create(true /* bare */);

        if (head != null) {
          RefUpdate u = repo.updateRef(Constants.HEAD);
          u.disableRefLog();
          u.link(head);
        }
      } finally {
        repo.close();
      }
    } catch (IOException e) {
      repLog.error(String.format(
          "Error creating local repository %s:\n", uri.getPath()), e);
    }
  }

  private void createRemoteSsh(CredentialsProvider credsProvider, URIish uri,
      String head) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "mkdir -p " + quotedPath
            + " && cd " + quotedPath
            + " && git init --bare";
    if (head != null) {
      cmd = cmd + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(head);
    }
    OutputStream errStream = newErrorBufferStream();
    try {
      executeRemoteSsh(credsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      repLog.error(String.format(
             "Error creating remote repository at %s:\n"
          + "  Exception: %s\n"
          + "  Command: %s\n"
          + "  Output: %s",
          uri, e, cmd, errStream), e);
    }
  }

  private void deleteProject(CredentialsProvider credsProvider,
      URIish replicateURI) {
    if (!replicateURI.isRemote()) {
      deleteLocally(replicateURI);
      repLog.info("Deleted local repository: " + replicateURI);
    } else if (isSSH(replicateURI)) {
      deleteRemoteSsh(credsProvider, replicateURI);
      repLog.info("Deleted remote repository: " + replicateURI);
    } else {
      repLog.warn(String.format("Cannot delete project on remote site %s."
          + " Only local paths and SSH URLs are supported"
          + " for remote repository deletion", replicateURI));
    }
  }

  private static void deleteLocally(URIish uri) {
    try {
      recursivelyDelete(new File(uri.getPath()));
    } catch (IOException e) {
      repLog.error(String.format(
          "Error deleting local repository %s:\n",
          uri.getPath()), e);
    }
  }

  public static void recursivelyDelete(File dir) throws IOException {
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

  private void deleteRemoteSsh(CredentialsProvider credsProvider, URIish uri) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "rm -rf " + quotedPath;
    OutputStream errStream = newErrorBufferStream();
    try {
      executeRemoteSsh(credsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      repLog.error(String.format(
             "Error deleting remote repository at %s:\n"
          + "  Exception: %s\n"
          + "  Command: %s\n"
          + "  Output: %s",
          uri, e, cmd, errStream), e);
    }
  }

  private void updateHead(CredentialsProvider credsProvider,
      URIish replicateURI, String newHead) {
    if (!replicateURI.isRemote()) {
      updateHeadLocally(replicateURI, newHead);
    } else if (isSSH(replicateURI)) {
      updateHeadRemoteSsh(credsProvider, replicateURI, newHead);
    } else {
      repLog.warn(String.format(
          "Cannot update HEAD of project on remote site %s."
              + " Only local paths and SSH URLs are supported"
              + " for remote HEAD update.", replicateURI));
    }
  }

  private void updateHeadRemoteSsh(CredentialsProvider credsProvider,
      URIish uri, String newHead) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "cd " + quotedPath
            + " && git symbolic-ref HEAD " + QuotedString.BOURNE.quote(newHead);
    OutputStream errStream = newErrorBufferStream();
    try {
      executeRemoteSsh(credsProvider, uri, cmd, errStream);
    } catch (IOException e) {
      repLog.error(String.format(
             "Error updating HEAD of remote repository at %s to %s:\n"
          + "  Exception: %s\n"
          + "  Command: %s\n"
          + "  Output: %s",
          uri, newHead, e, cmd, errStream), e);
    }
  }

  private static void updateHeadLocally(URIish uri, String newHead) {
    try {
      Repository repo = new FileRepository(uri.getPath());
      try {
        if (newHead != null) {
          RefUpdate u = repo.updateRef(Constants.HEAD);
          u.link(newHead);
        }
      } finally {
        repo.close();
      }
    } catch (IOException e) {
      repLog.error(
          String.format("Failed to update HEAD of repository %s to %s",
              uri.getPath(), newHead), e);
    }
  }

  private void executeRemoteSsh(CredentialsProvider credsProvider, URIish uri,
      String cmd, OutputStream errStream) throws IOException {
    RemoteSession ssh = connect(credsProvider, uri);
    Process proc = ssh.exec(cmd, 0);
    proc.getOutputStream().close();
    StreamCopyThread out =
        new StreamCopyThread(proc.getInputStream(), errStream);
    StreamCopyThread err =
        new StreamCopyThread(proc.getErrorStream(), errStream);
    out.start();
    err.start();
    try {
      proc.waitFor();
      out.halt();
      err.halt();
    } catch (InterruptedException interrupted) {
      // Don't wait, drop out immediately.
    }
    ssh.disconnect();
  }

  private RemoteSession connect(CredentialsProvider credsProvider, URIish uri)
      throws TransportException {
    return sshSessionFactory.create().getSession(uri, credsProvider,
        FS.DETECTED, SSH_REMOTE_TIMEOUT);
  }

  private static OutputStream newErrorBufferStream() {
    return new OutputStream() {
      private final StringBuilder out = new StringBuilder();
      private final StringBuilder line = new StringBuilder();

      @Override
      public synchronized String toString() {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
          out.setLength(out.length() - 1);
        }
        return out.toString();
      }

      @Override
      public synchronized void write(final int b) {
        if (b == '\r') {
          return;
        }

        line.append((char) b);

        if (b == '\n') {
          out.append(line);
          line.setLength(0);
        }
      }
    };
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
}
