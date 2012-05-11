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

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.lifecycle.LifecycleListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.InternalUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Manages automatic replication to remote repositories. */
@Singleton
class RunningReplicationQueue implements
    LifecycleListener,
    GitReferenceUpdatedListener,
    NewProjectCreatedListener {
  static final Logger log = LoggerFactory.getLogger(RunningReplicationQueue.class);

  private final Injector injector;
  private final WorkQueue workQueue;
  private final List<Destination> configs;
  private final SchemaFactory<ReviewDb> database;
  private final RemoteSiteUser.Factory replicationUserFactory;
  private final InternalUser.Factory internalUserFactory;
  private final GitRepositoryManager gitRepositoryManager;
  private final GroupCache groupCache;
  boolean replicateAllOnPluginStart;

  @Inject
  RunningReplicationQueue(final Injector i, final WorkQueue wq, final SitePaths site,
      final RemoteSiteUser.Factory ruf, final InternalUser.Factory iuf,
      final SchemaFactory<ReviewDb> db,
      final GitRepositoryManager grm, GroupCache gc)
      throws ConfigInvalidException, IOException {
    injector = i;
    workQueue = wq;
    database = db;
    replicationUserFactory = ruf;
    internalUserFactory = iuf;
    gitRepositoryManager = grm;
    groupCache = gc;
    configs = allConfigs(new File(site.etc_dir, "replication.config"));
  }

  public void start() {
    for (Destination cfg : configs) {
      cfg.start(workQueue);
    }
  }

  public void stop() {
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
    for (final Destination cfg : configs) {
      for (final URIish uri : cfg.getURIs(project, urlMatch)) {
        cfg.schedule(project, PushOne.ALL_REFS, uri);
      }
    }
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    Project.NameKey project = new Project.NameKey(event.getProjectName());
    for (GitReferenceUpdatedListener.Update u : event.getUpdates()) {
      for (final Destination cfg : configs) {
        if (cfg.wouldPushRef(u.getRefName())) {
          for (final URIish uri : cfg.getURIs(project, null)) {
            cfg.schedule(project, u.getRefName(), uri);
          }
        }
      }
    }
  }

  static String replace(final String pat, final String key,
      final String val) {
    final int n = pat.indexOf("${" + key + "}");

    if (n != -1) {
      return pat.substring(0, n) + val + pat.substring(n + 3 + key.length());
    } else {
      return null;
    }
  }

  private List<Destination> allConfigs(final File cfgPath)
      throws ConfigInvalidException, IOException {
    final FileBasedConfig cfg = new FileBasedConfig(cfgPath, FS.DETECTED);
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
      throw new ConfigInvalidException("Config file " + cfg.getFile()
          + " is invalid: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new IOException("Cannot read " + cfg.getFile() + ": "
          + e.getMessage(), e);
    }

    replicateAllOnPluginStart = cfg.getBoolean(
        "gerrit", "replicateOnStartup",
        true);

    final List<Destination> r = new ArrayList<Destination>();
    for (final RemoteConfig c : allRemotes(cfg)) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      for (final URIish u : c.getURIs()) {
        if (u.getPath() == null || !u.getPath().contains("${name}")) {
          throw new ConfigInvalidException("remote." + c.getName() + ".url"
              + " \"" + u + "\" lacks ${name} placeholder in " + cfg.getFile());
        }
      }

      // In case if refspec destination for push is not set then we assume it is
      // equal to source
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        RefSpec spec = new RefSpec();
        spec = spec.setSourceDestination("refs/*", "refs/*");
        spec = spec.setForceUpdate(true);
        c.addPushRefSpec(spec);
      }

      r.add(new Destination(injector, c, cfg, database,
          replicationUserFactory, internalUserFactory,
          gitRepositoryManager, groupCache));
    }
    return Collections.unmodifiableList(r);
  }

  private List<RemoteConfig> allRemotes(final FileBasedConfig cfg)
      throws ConfigInvalidException {
    List<String> names = new ArrayList<String>(cfg.getSubsections("remote"));
    Collections.sort(names);

    final List<RemoteConfig> result = new ArrayList<RemoteConfig>(names.size());
    for (final String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException("remote " + name
            + " has invalid URL in " + cfg.getFile());
      }
    }
    return result;
  }

  @Override
  public void onNewProjectCreated(NewProjectCreatedListener.Event event) {
    if (configs.isEmpty()) {
      return;
    }

    Project.NameKey projectName = new Project.NameKey(event.getProjectName());
    for (Destination config : configs) {
      List<URIish> uriList = config.getURIs(projectName, "*");
      String[] adminUrls = config.getAdminUrls();
      boolean adminURLUsed = false;

      for (String url : adminUrls) {
        URIish adminURI = null;
        try {
          if (url != null && !url.isEmpty()) {
            adminURI = new URIish(url);
          }
        } catch (URISyntaxException e) {
          log.error("The URL '" + url + "' is invalid");
        }

        if (adminURI != null) {
          final String replacedPath =
              replace(adminURI.getPath(), "name", projectName.get());
          if (replacedPath != null) {
            adminURI = adminURI.setPath(replacedPath);
            if (usingSSH(adminURI)) {
              replicateProject(adminURI, event.getHeadName());
              adminURLUsed = true;
            } else {
              log.error("The adminURL '" + url
                  + "' is non-SSH which is not allowed");
            }
          }
        }
      }

      if (!adminURLUsed) {
        for (URIish uri : uriList) {
          replicateProject(uri, event.getHeadName());
        }
      }
    }
  }

  private void replicateProject(final URIish replicateURI, final String head) {
    if (!replicateURI.isRemote()) {
      replicateProjectLocally(replicateURI, head);
    } else if (usingSSH(replicateURI)) {
      replicateProjectOverSsh(replicateURI, head);
    } else {
      log.warn("Cannot create new project on remote site since neither the "
          + "connection method is SSH nor the replication target is local: "
          + replicateURI.toString());
      return;
    }
  }

  private void replicateProjectLocally(final URIish replicateURI,
      final String head) {
    try {
      final Repository repo = new FileRepository(replicateURI.getPath());
      try {
        repo.create(true /* bare */);

        final RefUpdate u = repo.updateRef(Constants.HEAD);
        u.disableRefLog();
        u.link(head);
      } finally {
        repo.close();
      }
    } catch (IOException e) {
      log.error("Failed to replicate project locally: "
          + replicateURI.getPath());
    }
  }

  private void replicateProjectOverSsh(final URIish replicateURI,
      final String head) {
    SshSessionFactory sshFactory = SshSessionFactory.getInstance();
    RemoteSession sshSession;
    String projectPath = QuotedString.BOURNE.quote(replicateURI.getPath());

    OutputStream errStream = createErrStream();
    String cmd =
        "mkdir -p " + projectPath + "&& cd " + projectPath
            + "&& git init --bare" + "&& git symbolic-ref HEAD "
            + QuotedString.BOURNE.quote(head);

    try {
      sshSession = sshFactory.getSession(replicateURI, null, FS.DETECTED, 0);
      Process proc = sshSession.exec(cmd, 0);
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
      sshSession.disconnect();
    } catch (IOException e) {
      log.error("Communication error when trying to replicate to: "
          + replicateURI.toString() + "\n" + "Error reported: "
          + e.getMessage() + "\n" + "Error in communication: "
          + errStream.toString());
    }
  }

  private OutputStream createErrStream() {
    return new OutputStream() {
      private StringBuilder all = new StringBuilder();
      private StringBuilder sb = new StringBuilder();

      @Override
      public String toString() {
        String r = all.toString();
        while (r.endsWith("\n"))
          r = r.substring(0, r.length() - 1);
        return r;
      }

      @Override
      public synchronized void write(final int b) {
        if (b == '\r') {
          return;
        }

        sb.append((char) b);

        if (b == '\n') {
          all.append(sb);
          sb.setLength(0);
        }
      }
    };
  }

  private boolean usingSSH(final URIish uri) {
    final String scheme = uri.getScheme();
    if (!uri.isRemote()) return false;
    if (scheme != null && scheme.toLowerCase().contains("ssh")) return true;
    if (scheme == null && uri.getHost() != null && uri.getPath() != null)
      return true;
    return false;
  }
}
