/*
 * Copyright 2015 CollabNet, Inc. All rights reserved.
 * http://www.collab.net
 */

package com.googlesource.gerrit.plugins.replication;

public interface DestinationConfiguration {

  int getDelay();
  int getRetryDelay();
  int getPoolThreads();
  int getLockErrorMaxRetries();
  String[] getUrls();
  String[] getProjects();
  String[] getAdminUrls();
  String[] getAuthGroupNames();
  String getRemoteNameStyle();
  boolean replicatePermissions();
  boolean createMissingRepos();
  boolean replicateHiddenProjects();
  boolean replicateProjectDeletions();
}
