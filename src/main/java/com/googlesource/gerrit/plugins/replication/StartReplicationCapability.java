package com.googlesource.gerrit.plugins.replication;

import com.google.gerrit.extensions.config.CapabilityDefinition;

public class StartReplicationCapability implements CapabilityDefinition {

  final static String START_REPLICATION = "startReplication";

  @Override
  public String getName() {
    return START_REPLICATION;
  }

  @Override
  public String getDescription() {
    return "Start Replication";
  }
}
