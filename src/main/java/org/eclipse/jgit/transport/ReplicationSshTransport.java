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

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.transport.GitProtocolConstants.CAPABILITY_ATOMIC;

import com.googlesource.gerrit.plugins.replication.JGitReplicationAccess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.errors.TooLargeObjectInPackException;
import org.eclipse.jgit.errors.TooLargePackException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.util.io.MessageWriter;
import org.eclipse.jgit.util.io.StreamCopyThread;

public class ReplicationSshTransport extends SshTransport {

  private final TransportGitSsh delegate;
  private Predicate<Ref> uninterestingObjectsRefFilter;

  public ReplicationSshTransport(TransportGitSsh delegate) {
    super(
        JGitReplicationAccess.transportLocal(delegate),
        JGitReplicationAccess.transportUri(delegate));
    this.delegate = delegate;
  }

  public void setUninterestingObjectsRefFilter(Predicate<Ref> filter) {
    this.uninterestingObjectsRefFilter = filter;
  }

  Predicate<Ref> getUninterestingObjectsRefFilter() {
    return uninterestingObjectsRefFilter;
  }

  @Override
  public void applyConfig(RemoteConfig cfg) {
    delegate.applyConfig(cfg);
    super.applyConfig(cfg);
  }

  @Override
  public void setCredentialsProvider(CredentialsProvider credentialsProvider) {
    delegate.setCredentialsProvider(credentialsProvider);
    super.setCredentialsProvider(credentialsProvider);
  }

  @Override
  public void setSshSessionFactory(SshSessionFactory factory) {
    delegate.setSshSessionFactory(factory);
    super.setSshSessionFactory(factory);
  }

  @Override
  public FetchConnection openFetch() throws TransportException {
    return delegate.openFetch();
  }

  @Override
  public FetchConnection openFetch(Collection<RefSpec> refSpecs, String... additionalPatterns)
      throws NotSupportedException, TransportException {
    return delegate.openFetch(refSpecs, additionalPatterns);
  }

  @Override
  public PushConnection openPush() throws TransportException {
    return new ReplicationSshPushConnection();
  }

  @Override
  public void close() {
    delegate.close();
    super.close();
  }

  class ReplicationSshPushConnection extends BasePackPushConnection {
    private final Process process;
    private StreamCopyThread errorThread;

    private boolean capableAtomic;
    private boolean capableDeleteRefs;
    private boolean capableReport;
    private boolean capableSideBand;
    private boolean capableOfsDelta;
    private boolean capablePushOptions;

    private boolean sentCommand;
    private boolean needWritePack;
    private long packTransferTime;

    ReplicationSshPushConnection() throws TransportException {
      super(ReplicationSshTransport.this.delegate);
      TransportGitSsh core = ReplicationSshTransport.this.delegate;
      try {
        process =
            JGitReplicationAccess.sshSession(ReplicationSshTransport.this)
                .exec(
                    JGitReplicationAccess.commandFor(core, core.getOptionReceivePack()),
                    core.getTimeout());
        MessageWriter msg = new MessageWriter();
        setMessageWriter(msg);

        InputStream rpErr = process.getErrorStream();
        errorThread = new StreamCopyThread(rpErr, msg.getRawStream());
        errorThread.start();

        init(process.getInputStream(), process.getOutputStream());

      } catch (TransportException err) {
        try {
          close();
        } catch (Exception e) {
          // ignore
        }
        throw err;
      } catch (Throwable err) {
        try {
          close();
        } catch (Exception e) {
          // ignore
        }
        throw new TransportException(uri, JGitText.get().remoteHungUpUnexpectedly, err);
      }

      try {
        readAdvertisedRefs();
      } catch (NoRemoteRepositoryException notFound) {
        String msgs = getMessages();
        JGitReplicationAccess.checkExecFailure(
            core, process.exitValue(), core.getOptionReceivePack(), msgs);
        throw JGitReplicationAccess.cleanNotFound(core, notFound, msgs);
      }
    }

    @Override
    public void close() {
      endOut();

      if (process != null) {
        process.destroy();
      }
      if (errorThread != null) {
        try {
          errorThread.halt();
        } catch (InterruptedException e) {
          // Stop waiting and return anyway.
        } finally {
          errorThread = null;
        }
      }

      super.close();
    }

    @Override
    protected void doPush(
        ProgressMonitor monitor, Map<String, RemoteRefUpdate> refUpdates, OutputStream outputStream)
        throws TransportException {
      try {
        writeCommands(refUpdates.values(), monitor, outputStream);

        List<String> pushOptions = getPushOptions();
        if (pushOptions != null && capablePushOptions) {
          transmitOptions(pushOptions);
        }
        if (needWritePack) {
          writePack(refUpdates, monitor);
        }
        if (sentCommand) {
          if (capableReport) {
            readStatusReport(refUpdates);
          }
          if (capableSideBand) {
            int b = in.read();
            if (0 <= b) {
              throw new TransportException(
                  uri,
                  MessageFormat.format(
                      JGitText.get().expectedEOFReceived, Character.valueOf((char) b)));
            }
          }
        }
      } catch (TransportException e) {
        throw e;
      } catch (Exception e) {
        throw new TransportException(uri, e.getMessage(), e);
      } finally {
        JGitReplicationAccess.drainSideBandIfAny(in);
        close();
      }
    }

    private void writeCommands(
        Collection<RemoteRefUpdate> refUpdates, ProgressMonitor monitor, OutputStream outputStream)
        throws IOException {
      String capabilities = enableCapabilities(monitor, outputStream);
      if (transport.isPushAtomic() && !capableAtomic) {
        throw new TransportException(uri, JGitText.get().atomicPushNotSupported);
      }

      List<String> pushOptions = getPushOptions();
      if (pushOptions != null && !capablePushOptions) {
        throw new TransportException(
            uri,
            MessageFormat.format(JGitText.get().pushOptionsNotSupported, pushOptions.toString()));
      }

      for (RemoteRefUpdate rru : refUpdates) {
        if (!capableDeleteRefs && rru.isDelete()) {
          JGitReplicationAccess.setStatus(rru, Status.REJECTED_NODELETE);
          continue;
        }

        StringBuilder sb = new StringBuilder();
        ObjectId oldId = rru.getExpectedOldObjectId();
        if (oldId == null) {
          Ref advertised = getRef(rru.getRemoteName());
          oldId = advertised != null ? advertised.getObjectId() : null;
          if (oldId == null) {
            oldId = ObjectId.zeroId();
          }
        }
        sb.append(oldId.name());
        sb.append(' ');
        sb.append(rru.getNewObjectId().name());
        sb.append(' ');
        sb.append(rru.getRemoteName());
        if (!sentCommand) {
          sentCommand = true;
          sb.append(capabilities);
        }

        pckOut.writeString(sb.toString());
        JGitReplicationAccess.setStatus(rru, Status.AWAITING_REPORT);
        if (!rru.isDelete()) {
          needWritePack = true;
        }
      }

      if (monitor.isCancelled()) {
        throw new TransportException(uri, JGitText.get().pushCancelled);
      }
      pckOut.end();
      outNeedsEnd = false;
    }

    private void transmitOptions(List<String> pushOptions) throws IOException {
      for (String pushOption : pushOptions) {
        pckOut.writeString(pushOption);
      }

      pckOut.end();
    }

    private String enableCapabilities(ProgressMonitor monitor, OutputStream outputStream) {
      StringBuilder line = new StringBuilder();
      if (transport.isPushAtomic()) {
        capableAtomic = wantCapability(line, CAPABILITY_ATOMIC);
      }
      capableReport = wantCapability(line, CAPABILITY_REPORT_STATUS);
      capableDeleteRefs = wantCapability(line, CAPABILITY_DELETE_REFS);
      capableOfsDelta = wantCapability(line, CAPABILITY_OFS_DELTA);

      List<String> pushOptions = getPushOptions();
      if (pushOptions != null) {
        capablePushOptions = wantCapability(line, CAPABILITY_PUSH_OPTIONS);
      }

      capableSideBand = wantCapability(line, CAPABILITY_SIDE_BAND_64K);
      if (capableSideBand) {
        in =
            JGitReplicationAccess.newSideBandInputStream(
                in, monitor, getMessageWriter(), outputStream);
        pckIn = new PacketLineIn(in);
      }
      addUserAgentCapability(line);

      if (line.length() > 0) {
        line.setCharAt(0, '\0');
      }
      return line.toString();
    }

    private void writePack(Map<String, RemoteRefUpdate> refUpdates, ProgressMonitor monitor)
        throws IOException {
      Set<ObjectId> remoteObjects = new HashSet<>();
      Set<ObjectId> newObjects = new HashSet<>();
      Predicate<Ref> filter = ReplicationSshTransport.this.getUninterestingObjectsRefFilter();

      try (PackWriter writer = new PackWriter(transport.getPackConfig(), local.newObjectReader())) {

        for (Ref r : getRefs()) {
          ObjectId oid = r.getObjectId();
          if ((filter == null || filter.test(r)) && local.getObjectDatabase().has(oid)) {
            remoteObjects.add(oid);
          }
        }
        remoteObjects.addAll(additionalHaves);
        for (RemoteRefUpdate r : refUpdates.values()) {
          if (!ObjectId.zeroId().equals(r.getNewObjectId())) {
            newObjects.add(r.getNewObjectId());
          }
        }

        writer.setIndexDisabled(true);
        writer.setUseCachedPacks(true);
        writer.setUseBitmaps(isUseBitmaps());
        writer.setThin(transport.isPushThin());
        writer.setReuseValidatingObjects(false);
        writer.setDeltaBaseAsOffset(capableOfsDelta);
        writer.preparePack(monitor, newObjects, remoteObjects);

        OutputStream packOut = out;
        if (capableSideBand) {
          packOut = new CheckingSideBandOutputStream(in, out);
        }
        writer.writePack(monitor, monitor, packOut);

        packTransferTime = writer.getStatistics().getTimeWriting();
      }
    }

    private void readStatusReport(Map<String, RemoteRefUpdate> refUpdates) throws IOException {
      String unpackLine = readStringLongTimeout();
      if (!unpackLine.startsWith("unpack ")) { // $NON-NLS-1$
        throw new PackProtocolException(
            MessageFormat.format(JGitText.get().unexpectedReportLine, unpackLine));
      }
      String unpackStatus = unpackLine.substring("unpack ".length()); // $NON-NLS-1$
      if (unpackStatus.startsWith("error Pack exceeds the limit of")) { // $NON-NLS-1$
        throw new TooLargePackException(
            uri, unpackStatus.substring("error ".length())); // $NON-NLS-1$
      } else if (unpackStatus.startsWith("error Object too large")) { // $NON-NLS-1$
        throw new TooLargeObjectInPackException(
            uri, unpackStatus.substring("error ".length())); // $NON-NLS-1$
      } else if (!unpackStatus.equals("ok")) { // $NON-NLS-1$
        throw new TransportException(
            uri,
            MessageFormat.format(
                JGitText.get().errorOccurredDuringUnpackingOnTheRemoteEnd, unpackStatus));
      }

      for (String refLine : pckIn.readStrings()) {
        boolean ok = false;
        int refNameEnd = -1;
        if (refLine.startsWith("ok ")) { // $NON-NLS-1$
          ok = true;
          refNameEnd = refLine.length();
        } else if (refLine.startsWith("ng ")) { // $NON-NLS-1$
          ok = false;
          refNameEnd = refLine.indexOf(' ', 3);
        }
        if (refNameEnd == -1) {
          throw new PackProtocolException(
              MessageFormat.format(JGitText.get().unexpectedReportLine2, uri, refLine));
        }
        String refName = refLine.substring(3, refNameEnd);
        String message = (ok ? null : refLine.substring(refNameEnd + 1));

        RemoteRefUpdate rru = refUpdates.get(refName);
        if (rru == null) {
          throw new PackProtocolException(
              MessageFormat.format(JGitText.get().unexpectedRefReport, uri, refName));
        }
        if (ok) {
          JGitReplicationAccess.setStatus(rru, Status.OK);
        } else {
          JGitReplicationAccess.setStatus(rru, Status.REJECTED_OTHER_REASON);
          JGitReplicationAccess.setMessage(rru, message);
        }
      }
      for (RemoteRefUpdate rru : refUpdates.values()) {
        if (rru.getStatus() == Status.AWAITING_REPORT) {
          throw new PackProtocolException(
              MessageFormat.format(
                  JGitText.get().expectedReportForRefNotReceived, uri, rru.getRemoteName()));
        }
      }
    }

    private String readStringLongTimeout() throws IOException {
      if (timeoutIn == null) {
        return pckIn.readString();
      }

      int oldTimeout = timeoutIn.getTimeout();
      int sendTime = (int) Math.min(packTransferTime, 28800000L);
      try {
        int timeout = 10 * Math.max(sendTime, oldTimeout);
        timeoutIn.setTimeout((timeout < 0) ? Integer.MAX_VALUE : timeout);
        return pckIn.readString();
      } finally {
        timeoutIn.setTimeout(oldTimeout);
      }
    }

    private static class CheckingSideBandOutputStream extends OutputStream {
      private final InputStream in;
      private final OutputStream out;

      CheckingSideBandOutputStream(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
      }

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b});
      }

      @Override
      public void write(byte[] buf, int ptr, int cnt) throws IOException {
        try {
          out.write(buf, ptr, cnt);
        } catch (IOException e) {
          throw checkError(e);
        }
      }

      @Override
      public void flush() throws IOException {
        try {
          out.flush();
        } catch (IOException e) {
          throw checkError(e);
        }
      }

      private IOException checkError(IOException e1) {
        try {
          in.read();
        } catch (TransportException e2) {
          return e2;
        } catch (IOException e2) {
          return e1;
        }
        return e1;
      }
    }
  }
}
