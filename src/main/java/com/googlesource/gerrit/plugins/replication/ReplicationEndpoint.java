package com.googlesource.gerrit.plugins.replication;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.reviewdb.client.Project;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.URIish;

public interface ReplicationEndpoint {

  class QueueInfo<T> {
    public final Map<URIish, T> pending;
    public final Map<URIish, T> inFlight;

    public QueueInfo(Map<URIish, T> pending, Map<URIish, T> inFlight) {
      this.pending = ImmutableMap.copyOf(pending);
      this.inFlight = ImmutableMap.copyOf(inFlight);
    }
  }

  QueueInfo getQueueInfo();

  boolean wouldReplicateProject(Project.NameKey project);

  List<URIish> getURIs(Project.NameKey project, String urlMatch);

  String getRemoteConfigName();

  ImmutableList<String> getUrls();

  ImmutableList<String> getAuthGroupNames();

  ImmutableList<String> getProjects();

  ImmutableList<String> getAdminUrls();

  boolean wouldReplicateRef(String ref);

  void schedule(Project.NameKey project, String ref, URIish uri, ReplicationState state);

  void schedule(
      Project.NameKey project, String ref, URIish uri, ReplicationState state, boolean now);
}
