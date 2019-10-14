package com.googlesource.gerrit.plugins.replication;

public interface ReplicationQueueInterface {

  boolean isRunning();

  boolean isReplaying();
}
