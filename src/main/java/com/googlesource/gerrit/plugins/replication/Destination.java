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

import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gerrit.common.data.AccessSection;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.config.FactoryModule;
import com.google.gerrit.server.config.RequestScopedReviewDbProvider;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.PerRequestProjectControlCache;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.util.RequestContext;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.servlet.RequestScoped;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

class Destination {
  private final int poolThreads;
  private final String poolName;

  private final RemoteConfig remote;
  private final String[] adminUrls;
  private final String[] projects;
  private final int delay;
  private final int retryDelay;
  private final Map<URIish, PushOne> pending = new HashMap<URIish, PushOne>();
  private final PushOne.Factory opFactory;
  private final ProjectControl.Factory projectControlFactory;
  private final GitRepositoryManager gitManager;
  private final boolean replicatePermissions;
  private final String remoteNameStyle;
  private volatile WorkQueue.Executor pool;
  private final PerThreadRequestScope.Scoper threadScoper;

  Destination(final Injector injector,
      final RemoteConfig rc,
      final Config cfg,
      final SchemaFactory<ReviewDb> db,
      final RemoteSiteUser.Factory replicationUserFactory,
      final PluginUser pluginUser,
      final GitRepositoryManager gitRepositoryManager,
      final GroupBackend groupBackend) {
    remote = rc;
    gitManager = gitRepositoryManager;
    delay = Math.max(0, getInt(rc, cfg, "replicationdelay", 15));
    retryDelay = Math.max(0, getInt(rc, cfg, "replicationretry", 1));
    adminUrls = cfg.getStringList("remote", rc.getName(), "adminUrl");

    poolThreads = Math.max(0, getInt(rc, cfg, "threads", 1));
    poolName = "ReplicateTo-" + rc.getName();
    replicatePermissions =
        cfg.getBoolean("remote", rc.getName(), "replicatePermissions", true);
    remoteNameStyle = Objects.firstNonNull(
        cfg.getString("remote", rc.getName(), "remoteNameStyle"), "slash");
    projects = cfg.getStringList("remote", rc.getName(), "projects");

    final CurrentUser remoteUser;
    String[] authGroupNames = cfg.getStringList("remote", rc.getName(), "authGroup");
    if (authGroupNames.length > 0) {
      ImmutableSet.Builder<AccountGroup.UUID> builder = ImmutableSet.builder();
      for (String name : authGroupNames) {
        GroupReference g = GroupBackends.findExactSuggestion(groupBackend, name);
        if (g != null) {
          builder.add(g.getUUID());
        } else {
          ReplicationQueue.log.warn(String.format(
              "Group \"%s\" not recognized, removing from authGroup", name));
        }
      }
      remoteUser = replicationUserFactory.create(
          new ListGroupMembership(builder.build()));
    } else {
      remoteUser = pluginUser;
    }

    Injector child = injector.createChildInjector(new FactoryModule() {
      @Override
      protected void configure() {
        bindScope(RequestScoped.class, PerThreadRequestScope.REQUEST);
        bind(PerThreadRequestScope.Propagator.class);
        bind(PerRequestProjectControlCache.class).in(RequestScoped.class);

        bind(Destination.class).toInstance(Destination.this);
        bind(RemoteConfig.class).toInstance(remote);
        install(new FactoryModuleBuilder().build(PushOne.Factory.class));
      }

      @Provides
      public PerThreadRequestScope.Scoper provideScoper(
          final PerThreadRequestScope.Propagator propagator,
          final Provider<RequestScopedReviewDbProvider> dbProvider) {
        final RequestContext requestContext = new RequestContext() {
          @Override
          public CurrentUser getCurrentUser() {
            return remoteUser;
          }

          @Override
          public Provider<ReviewDb> getReviewDbProvider() {
            return dbProvider.get();
          }
        };
        return new PerThreadRequestScope.Scoper() {
          @Override
          public <T> Callable<T> scope(Callable<T> callable) {
            return propagator.scope(requestContext, callable);
          }
        };
      }
    });

    projectControlFactory = child.getInstance(ProjectControl.Factory.class);
    opFactory = child.getInstance(PushOne.Factory.class);
    threadScoper = child.getInstance(PerThreadRequestScope.Scoper.class);
  }

  void start(WorkQueue workQueue) {
    pool = workQueue.createQueue(poolThreads, poolName);
  }

  int shutdown() {
    int cnt = 0;
    if (pool != null) {
      cnt = pool.shutdownNow().size();
      pool.unregisterWorkQueue();
      pool = null;
    }
    return cnt;
  }

  private static int getInt(
      RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }

  void schedule(final Project.NameKey project, final String ref,
      final URIish uri) {
    try {
      boolean visible = threadScoper.scope(new Callable<Boolean>(){
        @Override
        public Boolean call() throws NoSuchProjectException {
          return controlFor(project).isVisible();
        }
      }).call();
      if (!visible) {
        return;
      }
    } catch (NoSuchProjectException err) {
      ReplicationQueue.log.error(String.format(
          "source project %s not available", project), err);
      return;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }

    if (!replicatePermissions) {
      PushOne e;
      synchronized (pending) {
        e = pending.get(uri);
      }
      if (e == null) {
        Repository git;
        try {
          git = gitManager.openRepository(project);
        } catch (IOException err) {
          ReplicationQueue.log.error(String.format(
              "source project %s not available", project), err);
          return;
        }
        try {
          Ref head = git.getRef(Constants.HEAD);
          if (head != null
              && head.isSymbolic()
              && GitRepositoryManager.REF_CONFIG.equals(head.getLeaf().getName())) {
            return;
          }
        } catch (IOException err) {
          ReplicationQueue.log.error(String.format(
              "cannot check type of project %s", project), err);
          return;
        } finally {
          git.close();
        }
      }
    }

    synchronized (pending) {
      PushOne e = pending.get(uri);
      if (e == null) {
        e = opFactory.create(project, uri);
        pool.schedule(e, delay, TimeUnit.SECONDS);
        pending.put(uri, e);
      }
      e.addRef(ref);
    }
  }

  /**
   * It schedules again a PushOp instance.
   * <p>
   * It is assumed to be previously scheduled and found a
   * transport exception. It will schedule it as a push
   * operation to be retried after the minutes count
   * determined by class attribute retryDelay.
   * <p>
   * In case the PushOp instance to be scheduled has same
   * URI than one also pending for retry, it adds to the one
   * pending the refs list of the parameter instance.
   * <p>
   * In case the PushOp instance to be scheduled has same
   * URI than one pending, but not pending for retry, it
   * indicates the one pending should be canceled when it
   * starts executing, removes it from pending list, and
   * adds its refs to the parameter instance. The parameter
   * instance is scheduled for retry.
   * <p>
   * Notice all operations to indicate a PushOp should be
   * canceled, or it is retrying, or remove/add it from/to
   * pending Map should be protected by the lock on pending
   * Map class instance attribute.
   *
   * @param pushOp The PushOp instance to be scheduled.
   */
  void reschedule(PushOne pushOp) {
    // It locks access to pending variable.
    synchronized (pending) {
      URIish uri = pushOp.getURI();
      PushOne pendingPushOp = pending.get(uri);

      if (pendingPushOp != null) {
        // There is one PushOp instance already pending to same URI.

        if (pendingPushOp.isRetrying()) {
          // The one pending is one already retrying, so it should
          // maintain it and add to it the refs of the one passed
          // as parameter to the method.

          // This scenario would happen if a PushOp has started running
          // and then before it failed due transport exception, another
          // one to same URI started. The first one would fail and would
          // be rescheduled, being present in pending list. When the
          // second one fails, it will also be rescheduled and then,
          // here, find out replication to its URI is already pending
          // for retry (blocking).
          pendingPushOp.addRefs(pushOp.getRefs());

        } else {
          // The one pending is one that is NOT retrying, it was just
          // scheduled believing no problem would happen. The one pending
          // should be canceled, and this is done by setting its canceled
          // flag, removing it from pending list, and adding its refs to
          // the pushOp instance that should then, later, in this method,
          // be scheduled for retry.

          // Notice that the PushOp found pending will start running and,
          // when notifying it is starting (with pending lock protection),
          // it will see it was canceled and then it will do nothing with
          // pending list and it will not execute its run implementation.

          pendingPushOp.cancel();
          pending.remove(uri);

          pushOp.addRefs(pendingPushOp.getRefs());
        }
      }

      if (pendingPushOp == null || !pendingPushOp.isRetrying()) {
        // The PushOp method param instance should be scheduled for retry.
        // Remember when retrying it should be used different delay.

        pushOp.setToRetry();

        pending.put(uri, pushOp);
        pool.schedule(pushOp, retryDelay, TimeUnit.MINUTES);
      }
    }
  }

  ProjectControl controlFor(Project.NameKey project)
      throws NoSuchProjectException {
    return projectControlFactory.controlFor(project);
  }

  void notifyStarting(PushOne op) {
    synchronized (pending) {
      if (!op.wasCanceled()) {
        pending.remove(op.getURI());
      }
    }
  }

  boolean wouldPushProject(Project.NameKey project) {
    // by default push all projects
    if (projects.length < 1) {
      return true;
    }

    String projectName = project.get();
    for (final String projectMatch : projects) {
      if (isRE(projectMatch)) {
        // projectMatch is a regular expression
        if (projectName.matches(projectMatch)) {
          return true;
        }
      } else if (isWildcard(projectMatch)) {
        // projectMatch is a wildcard
        if (projectName.startsWith(
            projectMatch.substring(0, projectMatch.length() - 1))) {
          return true;
        }
      } else {
        // No special case, so we try to match directly
        if (projectName.equals(projectMatch)) {
          return true;
        }
      }
    }

    // Nothing matched, so don't push the project
    return false;
  }

  boolean isSingleProjectMatch() {
    boolean ret = (projects.length == 1);
    if (ret) {
      String projectMatch = projects[0];
      if (isRE(projectMatch) || isWildcard(projectMatch)) {
        // projectMatch is either regular expression, or wild-card.
        //
        // Even though they might refer to a single project now, they need not
        // after new projects have been created. Hence, we do not treat them as
        // matching a single project.
        ret = false;
      }
    }
    return ret;
  }

  private static boolean isRE(String str) {
    return str.startsWith(AccessSection.REGEX_PREFIX);
  }

  private static boolean isWildcard(String str) {
    return str.endsWith("*");
  }

  boolean wouldPushRef(String ref) {
    if (!replicatePermissions && GitRepositoryManager.REF_CONFIG.equals(ref)) {
      return false;
    }
    for (RefSpec s : remote.getPushRefSpecs()) {
      if (s.matchSource(ref)) {
        return true;
      }
    }
    return false;
  }

  boolean isReplicatePermissions() {
    return replicatePermissions;
  }

  List<URIish> getURIs(Project.NameKey project, String urlMatch) {
    List<URIish> r = Lists.newArrayListWithCapacity(remote.getURIs().size());
    for (URIish uri : remote.getURIs()) {
      if (matches(uri, urlMatch)) {
        String name = project.get();
        if (needsUrlEncoding(uri)) {
          name = encode(name);
        }
        if (remoteNameStyle.equals("dash")) {
          name = name.replace("/", "-");
        } else if(remoteNameStyle.equals("underscore")) {
          name = name.replace("/", "_");
        } else if (!remoteNameStyle.equals("slash")) {
            ReplicationQueue.log.debug(String.format(
                "Unknown remoteNameStyle: %s, falling back to slash", remoteNameStyle));
        }
        String replacedPath = ReplicationQueue.replaceName(uri.getPath(), name,
            isSingleProjectMatch());
        if (replacedPath != null) {
          uri = uri.setPath(replacedPath);
          r.add(uri);
        }
      }
    }
    return r;
  }

  static boolean needsUrlEncoding(URIish uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
      || "https".equalsIgnoreCase(uri.getScheme())
      || "amazon-s3".equalsIgnoreCase(uri.getScheme());
  }

  static String encode(String str) {
    try {
      // Some cleanup is required. The '/' character is always encoded as %2F
      // however remote servers will expect it to be not encoded as part of the
      // path used to the repository. Space is incorrectly encoded as '+' for this
      // context. In the path part of a URI space should be %20, but in form data
      // space is '+'. Our cleanup replace fixes these two issues.
      return URLEncoder.encode(str, "UTF-8")
        .replaceAll("%2[fF]", "/")
        .replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  String[] getAdminUrls() {
    return adminUrls;
  }

  private static boolean matches(URIish uri, String urlMatch) {
    if (urlMatch == null || urlMatch.equals("") || urlMatch.equals("*")) {
      return true;
    }
    return uri.toString().contains(urlMatch);
  }
}
