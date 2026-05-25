// Copyright (C) 2026 The Android Open Source Project
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

import static java.util.stream.Collectors.toMap;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig.FilterType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.args4j.Option;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name = "list-outdated", description = "List outdated replication state")
final class ListOutdatedCommand extends SshCommand {
  private static final String NULL_SHA = ObjectId.zeroId().getName();

  private final Set<String> projects = new HashSet<>();
  private final Set<String> remotes = new HashSet<>();

  @Option(
      name = "--project",
      metaVar = "PROJECT",
      usage = "project to check (repeatable, defaults to all)")
  void addProject(String project) {
    projects.add(project);
  }

  @Option(
      name = "--remote",
      metaVar = "REMOTE",
      usage = "remote to check (repeatable, defaults to all)")
  void addRemote(String remote) {
    remotes.add(remote);
  }

  @Option(name = "--by-ref", usage = "list outdated state by ref instead of just by project")
  private boolean byRef;

  @Inject private ReplicationDestinations destinations;
  @Inject private GitRepositoryManager gitManager;
  @Inject private TransportFactory transportFactory;
  @Inject private CredentialsFactory credentialsFactory;
  @Inject private ProjectCache projectCache;

  @Override
  protected void run() throws Failure {
    List<Destination> selectedDests =
        destinations.getAll(FilterType.ALL).stream()
            .filter(d -> remotes.isEmpty() || remotes.contains(d.getRemoteConfigName()))
            .toList();
    if (selectedDests.isEmpty()) {
      stderr.println("error: no matching remotes found");
      return;
    }

    List<Project.NameKey> selectedProjects =
        projects.isEmpty()
            ? List.copyOf(projectCache.all())
            : projects.stream().map(Project::nameKey).toList();

    for (Project.NameKey project : selectedProjects) {
      check(project, selectedDests);
    }
  }

  void check(Project.NameKey project, List<Destination> dests) {
    try (Repository repo = gitManager.openRepository(project)) {
      Map<String, ObjectId> localRefs =
          repo.getRefDatabase().getRefs().stream()
              .collect(Collectors.toMap(Ref::getName, Ref::getObjectId));
      for (Destination dest : dests) {
        RemoteConfig remoteConfig = dest.getRemoteConfig();
        for (URIish uri : dest.getURIs(project, null)) {
          printOutdated(
              project,
              dest,
              uri,
              outdatedRefs(
                  localRefs,
                  lsRemote(repo, remoteConfig, uri),
                  remoteConfig.isMirror(),
                  remoteConfig.getPushRefSpecs(),
                  dest::canPushRef));
        }
      }
    } catch (RepositoryNotFoundException e) {
      printError(project, "repository not found");
    } catch (IOException e) {
      printError(project, e.getMessage());
    }
  }

  Map<String, ObjectId> lsRemote(Repository repo, RemoteConfig remoteConfig, URIish uri)
      throws IOException {
    try (Transport tn = transportFactory.open(repo, uri)) {
      tn.applyConfig(remoteConfig);
      tn.setCredentialsProvider(credentialsFactory.create(remoteConfig.getName()));
      try (FetchConnection fc = tn.openFetch()) {
        return fc.getRefsMap().entrySet().stream()
            .filter(e -> e.getValue().getObjectId() != null)
            .collect(toMap(Map.Entry::getKey, e -> e.getValue().getObjectId()));
      }
    }
  }

  static List<OutdatedRef> outdatedRefs(
      Map<String, ObjectId> localRefs,
      Map<String, ObjectId> remoteRefs,
      boolean isMirror,
      List<RefSpec> pushSpecs,
      Predicate<String> canPushRef) {
    List<OutdatedRef> outdated = new ArrayList<>();
    Set<String> coveredRemoteRefs = new HashSet<>();
    for (Map.Entry<String, ObjectId> localRef : localRefs.entrySet()) {
      if (!canPushRef.test(localRef.getKey())) {
        continue;
      }
      RefSpec matched = RefSpecMatcher.matchSource(localRef.getKey(), pushSpecs);
      if (matched == null) {
        continue;
      }

      coveredRemoteRefs.add(matched.getDestination());
      ObjectId remoteId = remoteRefs.get(matched.getDestination());
      if (!localRef.getValue().equals(remoteId)) {
        outdated.add(
            new OutdatedRef(
                localRef.getKey(),
                matched.getDestination(),
                localRef.getValue().name(),
                remoteId == null ? NULL_SHA : remoteId.getName()));
      }
    }

    // Reverse check to see if deletions are replicated.
    if (isMirror) {
      for (Map.Entry<String, ObjectId> e : remoteRefs.entrySet()) {
        String remoteName = e.getKey();
        if (Constants.HEAD.equals(remoteName) || coveredRemoteRefs.contains(remoteName)) {
          continue;
        }
        RefSpec matched = RefSpecMatcher.matchDestination(remoteName, pushSpecs);
        if (matched != null && canPushRef.test(matched.getSource())) {
          outdated.add(
              new OutdatedRef(matched.getSource(), remoteName, NULL_SHA, e.getValue().name()));
        }
      }
    }
    return outdated;
  }

  void printOutdated(
      Project.NameKey project, Destination dest, URIish uri, List<OutdatedRef> outdated) {
    if (outdated.isEmpty()) {
      return;
    }
    String label = dest.getRemoteConfigName() + "\t" + uri + "\t" + project.get();
    if (byRef) {
      for (OutdatedRef r : outdated) {
        stdout.println(
            label
                + "\t"
                + r.name()
                + "\t"
                + r.remoteName()
                + "\t"
                + r.primarySha()
                + "\t"
                + r.mirrorSha());
      }
    } else {
      stdout.println(label);
    }
  }

  void printError(Project.NameKey project, String msg) {
    stderr.println("error: " + project.get() + ": " + msg);
  }

  record OutdatedRef(String name, String remoteName, String primarySha, String mirrorSha) {}
}
