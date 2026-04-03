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

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.RemoteSession;
import org.eclipse.jgit.transport.SideBandInputStream;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportGitSsh;
import org.eclipse.jgit.transport.URIish;

public final class JGitReplicationAccess {
  private static final Method SET_STATUS;
  private static final Method SET_MESSAGE;
  private static final Constructor<?> SIDE_BAND_IN_CTOR;
  private static final Method SIDE_BAND_DRAIN;
  private static final Method SSH_GET_SESSION;
  private static final Field TRANSPORT_LOCAL;
  private static final Field TRANSPORT_URI;
  private static final Method TG_COMMAND_FOR;
  private static final Method TG_CHECK_EXEC_FAILURE;
  private static final Method TG_CLEAN_NOT_FOUND;

  static {
    try {
      SET_STATUS = RemoteRefUpdate.class.getDeclaredMethod("setStatus", Status.class);
      SET_STATUS.setAccessible(true);
      SET_MESSAGE = RemoteRefUpdate.class.getDeclaredMethod("setMessage", String.class);
      SET_MESSAGE.setAccessible(true);
      SIDE_BAND_IN_CTOR =
          SideBandInputStream.class.getDeclaredConstructor(
              InputStream.class, ProgressMonitor.class, Writer.class, OutputStream.class);
      SIDE_BAND_IN_CTOR.setAccessible(true);
      SIDE_BAND_DRAIN = SideBandInputStream.class.getDeclaredMethod("drainMessages");
      SIDE_BAND_DRAIN.setAccessible(true);
      SSH_GET_SESSION = SshTransport.class.getDeclaredMethod("getSession");
      SSH_GET_SESSION.setAccessible(true);
      TRANSPORT_LOCAL = Transport.class.getDeclaredField("local");
      TRANSPORT_LOCAL.setAccessible(true);
      TRANSPORT_URI = Transport.class.getDeclaredField("uri");
      TRANSPORT_URI.setAccessible(true);
      TG_COMMAND_FOR = TransportGitSsh.class.getDeclaredMethod("commandFor", String.class);
      TG_COMMAND_FOR.setAccessible(true);
      TG_CHECK_EXEC_FAILURE =
          TransportGitSsh.class.getDeclaredMethod(
              "checkExecFailure", int.class, String.class, String.class);
      TG_CHECK_EXEC_FAILURE.setAccessible(true);
      TG_CLEAN_NOT_FOUND =
          TransportGitSsh.class.getDeclaredMethod(
              "cleanNotFound", NoRemoteRepositoryException.class, String.class);
      TG_CLEAN_NOT_FOUND.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private JGitReplicationAccess() {}

  public static Repository transportLocal(Transport t) {
    try {
      return (Repository) TRANSPORT_LOCAL.get(t);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static URIish transportUri(Transport t) {
    try {
      return (URIish) TRANSPORT_URI.get(t);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static RemoteSession sshSession(SshTransport t) throws TransportException {
    try {
      return (RemoteSession) SSH_GET_SESSION.invoke(t);
    } catch (InvocationTargetException e) {
      Throwable c = e.getCause();
      if (c instanceof TransportException) {
        throw (TransportException) c;
      }
      throw new IllegalStateException(e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static InputStream newSideBandInputStream(
      InputStream in, ProgressMonitor monitor, Writer messages, OutputStream progressOut) {
    try {
      return (InputStream) SIDE_BAND_IN_CTOR.newInstance(in, monitor, messages, progressOut);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void drainSideBandIfAny(InputStream in) {
    if (!(in instanceof SideBandInputStream)) {
      return;
    }
    try {
      SIDE_BAND_DRAIN.invoke(in);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void setStatus(RemoteRefUpdate rru, Status status) {
    try {
      SET_STATUS.invoke(rru, status);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void setMessage(RemoteRefUpdate rru, String message) {
    try {
      SET_MESSAGE.invoke(rru, message);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String commandFor(TransportGitSsh t, String exe) {
    try {
      return (String) TG_COMMAND_FOR.invoke(t, exe);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void checkExecFailure(TransportGitSsh t, int status, String exe, String why)
      throws TransportException {
    try {
      TG_CHECK_EXEC_FAILURE.invoke(t, status, exe, why);
    } catch (InvocationTargetException e) {
      Throwable c = e.getCause();
      if (c instanceof TransportException) {
        throw (TransportException) c;
      }
      throw new IllegalStateException(e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  public static NoRemoteRepositoryException cleanNotFound(
      TransportGitSsh t, NoRemoteRepositoryException nf, String why) {
    try {
      return (NoRemoteRepositoryException) TG_CLEAN_NOT_FOUND.invoke(t, nf, why);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }
}
