// Copyright (C) 2024 NVIDIA
//
// Licensed under the Apache License, Version 2.0

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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
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
      List<Ref> localRefs = repo.getRefDatabase().getRefs();
      for (Destination dest : dests) {
        Map<Ref, String> mapped = applyRefSpec(localRefs, dest.getRemoteConfig().getPushRefSpecs());
        for (URIish uri : dest.getURIs(project, null)) {
          Map<String, ObjectId> remoteRefs = lsRemote(repo, dest.getRemoteConfig(), uri);
          List<OutdatedRef> outdated = outdatedRefs(mapped, remoteRefs);
          printOutdated(project, dest, uri, outdated);
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

  static Map<Ref, String> applyRefSpec(Collection<Ref> refs, List<RefSpec> pushRefSpecs) {
    Map<Ref, String> mapped = new HashMap<>();
    for (Ref ref : refs) {
      for (RefSpec spec : pushRefSpecs) {
        if (spec.matchSource(ref.getName())) {
          mapped.put(ref, spec.expandFromSource(ref.getName()).getDestination());
          break;
        }
      }
    }
    return mapped;
  }

  static List<OutdatedRef> outdatedRefs(
      Map<Ref, String> localToRemoteName, Map<String, ObjectId> remoteRefs) {
    List<OutdatedRef> outdated = new ArrayList<>();
    for (Map.Entry<Ref, String> e : localToRemoteName.entrySet()) {
      Ref local = e.getKey();
      String localName = local.getName();
      String remoteName = e.getValue();
      String localSha = local.getObjectId().name();
      ObjectId remoteId = remoteRefs.get(remoteName);
      if (remoteId == null) {
        outdated.add(new OutdatedRef(localName, remoteName, localSha, NULL_SHA));
      } else if (!local.getObjectId().equals(remoteId)) {
        outdated.add(new OutdatedRef(localName, remoteName, localSha, remoteId.getName()));
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
