package com.googlesource.gerrit.plugins.replication;

/**
 * Interface for notifying replication status updates.
 */
public interface ReplicationStateListener {

  /**
   * Notify a non-fatal replication error.
   *
   * Replication states received a non-fatal error with an associated
   * warning message.
   *
   * @param msg message description of the error
   * @param states replication states impacted
   */
  public abstract void warn(String msg, ReplicationState... states);

  /**
   * Notify a fatal replication error.
   *
   * Replication states have received a fatal error and replication has
   * failed.
   *
   * @param msg message description of the error
   * @param states replication states impacted
   */
  public abstract void error(String msg, ReplicationState... states);

  /**
   * Notify a fatal replication error with the associated exception.
   *
   * Replication states have received a fatal exception and replication has failed.
   *
   * @param msg message description of the error
   * @param t exception that caused the replication to fail
   * @param states replication states impacted
   */
  public abstract void error(String msg, Throwable t,
      ReplicationState... states);

}
