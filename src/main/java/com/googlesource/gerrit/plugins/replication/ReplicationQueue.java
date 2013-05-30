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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SshSessionFactory;
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
import java.util.List;
import java.util.Set;

/** Manages automatic replication to remote repositories. */
class ReplicationQueue implements
    LifecycleListener,
    GitReferenceUpdatedListener,
    NewProjectCreatedListener {
  static final Logger log = LoggerFactory.getLogger(ReplicationQueue.class);

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

  private final Injector injector;
  private final WorkQueue workQueue;
  private final List<Destination> configs;
  private final SchemaFactory<ReviewDb> database;
  private final RemoteSiteUser.Factory replicationUserFactory;
  private final PluginUser pluginUser;
  private final GitRepositoryManager gitRepositoryManager;
  private final GroupBackend groupBackend;
  private volatile boolean running;
  boolean replicateAllOnPluginStart;

  @Inject
  ReplicationQueue(final Injector i, final WorkQueue wq, final SitePaths site,
      final RemoteSiteUser.Factory ruf, final PluginUser pu,
      final SchemaFactory<ReviewDb> db,
      final GitRepositoryManager grm, final GroupBackend gb)
      throws ConfigInvalidException, IOException {
    injector = i;
    workQueue = wq;
    database = db;
    replicationUserFactory = ruf;
    pluginUser = pu;
    gitRepositoryManager = grm;
    groupBackend = gb;
    configs = allDestinations(new File(site.etc_dir, "replication.config"));
  }

  @Override
  public void start() {
    for (Destination cfg : configs) {
      cfg.start(workQueue);
    }
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    int discarded = 0;
    for (Destination cfg : configs) {
      discarded += cfg.shutdown();
    }
    if (discarded > 0) {
      log.warn(String.format(
          "Cancelled %d replication events during shutdown",
          discarded));
    }
  }

  void scheduleFullSync(final Project.NameKey project, final String urlMatch) {
    if (!running) {
      log.warn("Replication plugin did not finish startup before event");
      return;
    }

    for (Destination cfg : configs) {
      if (cfg.wouldPushProject(project)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          cfg.schedule(project, PushOne.ALL_REFS, uri);
        }
      }
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    if (!running) {
      log.warn("Replication plugin did not finish startup before event");
      return;
    }

    Project.NameKey project = new Project.NameKey(event.getProjectName());
    for (GitReferenceUpdatedListener.Update u : event.getUpdates()) {
      for (Destination cfg : configs) {
        if (cfg.wouldPushProject(project) && cfg.wouldPushRef(u.getRefName())) {
          for (URIish uri : cfg.getURIs(project, null)) {
            cfg.schedule(project, u.getRefName(), uri);
          }
        }
      }
    }
  }

  private List<Destination> allDestinations(File cfgPath)
      throws ConfigInvalidException, IOException {
    FileBasedConfig cfg = new FileBasedConfig(cfgPath, FS.DETECTED);
    if (!cfg.getFile().exists()) {
      log.warn("No " + cfg.getFile() + "; not replicating");
      return Collections.emptyList();
    }
    if (cfg.getFile().length() == 0) {
      log.info("Empty " + cfg.getFile() + "; not replicating");
      return Collections.emptyList();
    }

    try {
      cfg.load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(String.format(
          "Config file %s is invalid: %s",cfg.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      throw new IOException(String.format(
          "Cannot read %s: %s", cfg.getFile(),  e.getMessage()), e);
    }

    replicateAllOnPluginStart = cfg.getBoolean(
        "gerrit", "replicateOnStartup",
        true);

    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfig c : allRemotes(cfg)) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // If destination for push is not set assume equal to source.
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        c.addPushRefSpec(new RefSpec()
          .setSourceDestination("refs/*", "refs/*")
          .setForceUpdate(true));
      }

      Destination destination = new Destination(injector, c, cfg, database,
          replicationUserFactory, pluginUser, gitRepositoryManager,
          groupBackend);

      if (!destination.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(String.format(
                "remote.%s.url \"%s\" lacks ${name} placeholder in %s",
                c.getName(), u, cfg.getFile()));
          }
        }
      }

      dest.add(destination);
    }
    return dest.build();
  }

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg)
      throws ConfigInvalidException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(String.format(
            "remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }
    return result;
  }

  @Override
  public void onNewProjectCreated(NewProjectCreatedListener.Event event) {
    if (configs.isEmpty()) {
      return;
    }
    if (!running) {
      log.error("Replication plugin did not finish startup before event");
      return;
    }

    Project.NameKey projectName = new Project.NameKey(event.getProjectName());
    for (Destination config : configs) {
      if (!config.wouldPushProject(projectName)) {
        continue;
      }
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
          log.warn(String.format("adminURL '%s' is invalid: %s", url, e.getMessage()));
          continue;
        }

        String path = replaceName(uri.getPath(), projectName.get(),
            config.isSingleProjectMatch());
        if (path == null) {
          log.warn(String.format("adminURL %s does not contain ${name}", uri));
          continue;
        }

        uri = uri.setPath(path);
        if (!isSSH(uri)) {
          log.warn(String.format("adminURL '%s' is invalid: only SSH is supported", uri));
          continue;
        }

        createProject(uri, event.getHeadName());
        adminURLUsed = true;
      }

      if (!adminURLUsed) {
        for (URIish uri : uriList) {
          createProject(uri, event.getHeadName());
        }
      }
    }
  }

  private void createProject(URIish replicateURI, String head) {
    if (!replicateURI.isRemote()) {
      createLocally(replicateURI, head);
    } else if (isSSH(replicateURI)) {
      createRemoteSsh(replicateURI, head);
    } else {
      log.warn(String.format("Cannot create new project on remote site %s."
          + " Only local paths and SSH URLs are supported"
          + " for remote repository creation", replicateURI));
    }
  }

  private static void createLocally(URIish uri, String head) {
    try {
      Repository repo = new FileRepository(uri.getPath());
      try {
        repo.create(true /* bare */);

        RefUpdate u = repo.updateRef(Constants.HEAD);
        u.disableRefLog();
        u.link(head);
      } finally {
        repo.close();
      }
    } catch (IOException e) {
      log.error(String.format("Failed to create repository %s", uri.getPath()), e);
    }
  }

  private static void createRemoteSsh(URIish uri, String head) {
    String quotedPath = QuotedString.BOURNE.quote(uri.getPath());
    String cmd = "mkdir -p " + quotedPath
            + "&& cd " + quotedPath
            + "&& git init --bare"
            + "&& git symbolic-ref HEAD " + QuotedString.BOURNE.quote(head);
    OutputStream errStream = newErrorBufferStream();
    try {
      RemoteSession ssh = connect(uri);
      Process proc = ssh.exec(cmd, 0);
      proc.getOutputStream().close();
      StreamCopyThread out = new StreamCopyThread(proc.getInputStream(), errStream);
      StreamCopyThread err = new StreamCopyThread(proc.getErrorStream(), errStream);
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
    } catch (IOException e) {
      log.error(String.format(
             "Error creating remote repository at %s:\n"
          + "  Exception: %s\n"
          + "  Command: %s\n"
          + "  Output: %s",
          uri, e, cmd, errStream), e);
    }
  }

  private static RemoteSession connect(URIish uri) throws TransportException {
    return SshSessionFactory.getInstance().getSession(uri, null, FS.DETECTED, 0);
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
