// Copyright (C) 2019 The Android Open Source Project
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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.eclipse.jgit.lib.Ref.Storage.NEW;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackend.ForProject;
import com.google.gerrit.server.permissions.PermissionBackend.WithUser;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.util.IdGenerator;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import junit.framework.AssertionFailedError;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.easymock.IArgumentMatcher;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;

public class PushOneTest {
  private static int MAX_RETRY_COUNT = 100;
  private GitRepositoryManager gitRepositoryManagerMock;
  private Repository repositoryMock;
  private PermissionBackend permissionBackendMock;
  private PermissionBackend.WithUser withUserMock;
  private PermissionBackend.ForProject forProjectMock;

  private Destination destinationMock;
  private RemoteConfig remoteConfigMock;
  private RefSpec refSpecMock;
  private CredentialsFactory credentialsFactory;
  private PerThreadRequestScope.Scoper threadRequestScoperMock;
  private ReplicationQueue replicationQueueMock;
  private IdGenerator idGeneratorMock;
  private ReplicationStateListeners replicationStateListenersMock;
  private ReplicationMetrics replicationMetricsMock;
  private Timer1.Context timerContextMock;
  private ProjectCache projectCacheMock;
  private TransportFactory transportFactoryMock;
  private Transport transportMock;
  private FetchConnection fetchConnection;
  private PushConnection pushConnection;
  private ProjectState projectStateMock;
  private RefUpdate refUpdateMock;

  private Project.NameKey projectNameKey;
  private URIish urish;
  private Map<String, Ref> localRefs;

  private Map<String, Ref> remoteRefs;

  private AtomicBoolean isCallFinished = new AtomicBoolean(false);

  @Before
  public void setup() throws Exception {
    projectNameKey = new Project.NameKey("fooProject");
    urish = new URIish("http://foo.com/fooProject.git");

    Ref newLocalRef =
        new ObjectIdRef.Unpeeled(
            NEW, "foo", ObjectId.fromString("0000000000000000000000000000000000000001"));

    localRefs = new HashMap<>();
    localRefs.put("fooProject", newLocalRef);

    Ref remoteRef = new ObjectIdRef.Unpeeled(NEW, "foo", ObjectId.zeroId());
    remoteRefs = new HashMap<>();
    remoteRefs.put("fooProject", remoteRef);

    setupMocks();
  }

  private void setupMocks() throws Exception {
    FileBasedConfig config = new FileBasedConfig(new Config(), new File("/foo"), FS.DETECTED);
    config.setString("remote", "Replication", "push", "foo");

    setupRefUpdateMock();
    setupRepositoryMock(config);
    setupGitRepoManagerMock();

    projectStateMock = createNiceMock(ProjectState.class);
    forProjectMock = createNiceMock(ForProject.class);
    setupWithUserMock();
    setupPermissionBackedMock();

    setupDestinationMock();

    setupRefSpecMock();
    setupRemoteConfigMock();

    credentialsFactory = createNiceMock(CredentialsFactory.class);

    setupFetchConnectionMock();
    setupPushConnectionMock();
    setupRequestScopeMock();
    replicationQueueMock = createNiceMock(ReplicationQueue.class);
    idGeneratorMock = createNiceMock(IdGenerator.class);
    replicationStateListenersMock = createNiceMock(ReplicationStateListeners.class);

    timerContextMock = createNiceMock(Timer1.Context.class);
    setupReplicationMetricsMock();

    setupTransportMock();

    setupProjectCacheMock();

    replay(
        gitRepositoryManagerMock,
        refUpdateMock,
        repositoryMock,
        permissionBackendMock,
        destinationMock,
        remoteConfigMock,
        credentialsFactory,
        threadRequestScoperMock,
        replicationQueueMock,
        idGeneratorMock,
        replicationStateListenersMock,
        replicationMetricsMock,
        projectCacheMock,
        timerContextMock,
        transportFactoryMock,
        projectStateMock,
        withUserMock,
        forProjectMock,
        fetchConnection,
        pushConnection,
        refSpecMock);
  }

  @Test
  public void shouldPushAllRefsWhenNoFilters() throws InterruptedException, IOException {
    DynamicItem<ReplicationPushFilter> replicationPushFilter =
        DynamicItem.itemOf(ReplicationPushFilter.class, null);

    List<RemoteRefUpdate> expectedUpdates =
        localRefs.values().stream()
            .map(
                ref -> {
                  try {
                    return new RemoteRefUpdate(
                        repositoryMock,
                        ref.getName(),
                        ref.getObjectId(),
                        "fooProject",
                        false,
                        "fooProject",
                        null);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());

    PushResult pushResult = new PushResult();

    expect(transportMock.push(anyObject(), compareRemoteRef(expectedUpdates)))
        .andReturn(pushResult)
        .once();
    replay(transportMock);

    PushOne pushOne = createPushOne(replicationPushFilter);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    waitUntilFinished();

    verify(transportMock);
  }

  @Test
  public void shouldPushAllRefsWhenNoFiltersSetup() throws InterruptedException, IOException {

    List<RemoteRefUpdate> expectedUpdates =
        localRefs.values().stream()
            .map(
                ref -> {
                  try {
                    return new RemoteRefUpdate(
                        repositoryMock,
                        ref.getName(),
                        ref.getObjectId(),
                        "fooProject",
                        false,
                        "fooProject",
                        null);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());

    PushResult pushResult = new PushResult();

    expect(transportMock.push(anyObject(), compareRemoteRef(expectedUpdates)))
        .andReturn(pushResult)
        .once();
    replay(transportMock);

    PushOne pushOne = createPushOne(null);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    waitUntilFinished();

    verify(transportMock);
  }

  @Test
  public void shouldApplyReplicationPushFilter() throws InterruptedException, IOException {
    DynamicItem<ReplicationPushFilter> replicationPushFilter =
        DynamicItem.itemOf(
            ReplicationPushFilter.class,
            new ReplicationPushFilter() {

              @Override
              public List<RemoteRefUpdate> filter(
                  String projectName, List<RemoteRefUpdate> remoteUpdatesList) {
                remoteUpdatesList.remove(0);
                return remoteUpdatesList;
              }
            });

    // easymock way to check if method was never called
    expect(transportMock.push(anyObject(), anyObject()))
        .andThrow(new AssertionFailedError())
        .anyTimes();

    replay(transportMock);

    PushOne pushOne = createPushOne(replicationPushFilter);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    waitUntilFinished();

    verify(transportMock);
  }

  private PushOne createPushOne(DynamicItem<ReplicationPushFilter> replicationPushFilter) {
    PushOne push =
        new PushOne(
            gitRepositoryManagerMock,
            permissionBackendMock,
            destinationMock,
            remoteConfigMock,
            credentialsFactory,
            threadRequestScoperMock,
            replicationQueueMock,
            idGeneratorMock,
            replicationStateListenersMock,
            replicationMetricsMock,
            projectCacheMock,
            transportFactoryMock,
            projectNameKey,
            urish);

    push.setReplicationPushFilter(replicationPushFilter);
    return push;
  }

  private void waitUntilFinished() throws InterruptedException {
    int retryCount = 0;
    while (!isCallFinished.get()) {
      Thread.sleep(100);
      if (retryCount++ > MAX_RETRY_COUNT) {
        fail("Waiting until call finished - maximum number of retires reached");
      }
    }
  }

  private void setupProjectCacheMock() throws IOException {
    projectCacheMock = createNiceMock(ProjectCache.class);
    expect(projectCacheMock.checkedGet(projectNameKey)).andReturn(projectStateMock);
  }

  private void setupTransportMock() throws NotSupportedException, TransportException {
    transportMock = createNiceMock(Transport.class);
    expect(transportMock.openFetch()).andReturn(fetchConnection);
    transportFactoryMock = createNiceMock(TransportFactory.class);
    expect(transportFactoryMock.open(repositoryMock, urish)).andReturn(transportMock).anyTimes();
  }

  private void setupReplicationMetricsMock() {
    replicationMetricsMock = createNiceMock(ReplicationMetrics.class);
    expect(replicationMetricsMock.start(anyObject())).andReturn(timerContextMock);
  }

  private void setupRequestScopeMock() {
    threadRequestScoperMock = createNiceMock(PerThreadRequestScope.Scoper.class);
    expect(threadRequestScoperMock.scope(anyObject()))
        .andAnswer(
            new IAnswer<Callable<Object>>() {
              @SuppressWarnings("unchecked")
              @Override
              public Callable<Object> answer() throws Throwable {
                Callable<Object> originalCall = (Callable<Object>) getCurrentArguments()[0];
                return new Callable<Object>() {

                  @Override
                  public Object call() throws Exception {
                    Object result = originalCall.call();
                    isCallFinished.set(true);
                    return result;
                  }
                };
              }
            })
        .anyTimes();
  }

  private void setupPushConnectionMock() {
    pushConnection = createNiceMock(PushConnection.class);
    expect(pushConnection.getRefsMap()).andReturn(remoteRefs);
  }

  private void setupFetchConnectionMock() {
    fetchConnection = createNiceMock(FetchConnection.class);
    expect(fetchConnection.getRefsMap()).andReturn(remoteRefs);
  }

  private void setupRemoteConfigMock() {
    remoteConfigMock = createNiceMock(RemoteConfig.class);
    expect(remoteConfigMock.getPushRefSpecs()).andReturn(ImmutableList.of(refSpecMock));
  }

  private void setupRefSpecMock() {
    refSpecMock = createNiceMock(RefSpec.class);
    expect(refSpecMock.matchSource(anyObject(String.class))).andReturn(true);
    expect(refSpecMock.expandFromSource(anyObject(String.class))).andReturn(refSpecMock);
    expect(refSpecMock.getDestination()).andReturn("fooProject").anyTimes();
    expect(refSpecMock.isForceUpdate()).andReturn(false).anyTimes();
  }

  private void setupDestinationMock() {
    destinationMock = createNiceMock(Destination.class);
    expect(destinationMock.requestRunway(anyObject())).andReturn(RunwayStatus.allowed());
  }

  private void setupPermissionBackedMock() {
    permissionBackendMock = createNiceMock(PermissionBackend.class);
    expect(permissionBackendMock.currentUser()).andReturn(withUserMock);
  }

  private void setupWithUserMock() {
    withUserMock = createNiceMock(WithUser.class);
    expect(withUserMock.project(projectNameKey)).andReturn(forProjectMock);
  }

  private void setupGitRepoManagerMock() throws IOException {
    gitRepositoryManagerMock = createNiceMock(GitRepositoryManager.class);
    expect(gitRepositoryManagerMock.openRepository(projectNameKey)).andReturn(repositoryMock);
  }

  private void setupRepositoryMock(FileBasedConfig config) throws IOException {
    repositoryMock = createNiceMock(Repository.class);
    expect(repositoryMock.getConfig()).andReturn(config).anyTimes();
    expect(repositoryMock.getAllRefs()).andReturn(localRefs);
    expect(repositoryMock.updateRef("fooProject")).andReturn(refUpdateMock);
  }

  private void setupRefUpdateMock() {
    refUpdateMock = createNiceMock(RefUpdate.class);
    expect(refUpdateMock.getOldObjectId())
        .andReturn(ObjectId.fromString("0000000000000000000000000000000000000001"))
        .anyTimes();
  }

  private static Collection<RemoteRefUpdate> compareRemoteRef(
      Collection<RemoteRefUpdate> expectedRemoteRefs) {
    EasyMock.reportMatcher(new RemoteRefUpdateCollectionMatcher(expectedRemoteRefs));
    return null;
  }

  private static class RemoteRefUpdateCollectionMatcher implements IArgumentMatcher {
    Collection<RemoteRefUpdate> expectedRemoteRefs;

    public RemoteRefUpdateCollectionMatcher(Collection<RemoteRefUpdate> expectedRemoteRefs) {
      this.expectedRemoteRefs = expectedRemoteRefs;
    }

    @Override
    public boolean matches(Object argument) {
      if (!(argument instanceof Collection)) return false;

      @SuppressWarnings("unchecked")
      Collection<RemoteRefUpdate> refs = (Collection<RemoteRefUpdate>) argument;

      if (expectedRemoteRefs.size() != refs.size()) return false;
      return refs.stream()
          .allMatch(
              ref ->
                  expectedRemoteRefs.stream().anyMatch(expectedRef -> compare(ref, expectedRef)));
    }

    @Override
    public void appendTo(StringBuffer buffer) {
      buffer.append("expected:" + expectedRemoteRefs.toString());
    }

    private boolean compare(RemoteRefUpdate ref, RemoteRefUpdate expectedRef) {
      return Objects.equals(ref.getRemoteName(), expectedRef.getRemoteName())
          && Objects.equals(ref.getStatus(), expectedRef.getStatus())
          && Objects.equals(ref.getExpectedOldObjectId(), expectedRef.getExpectedOldObjectId())
          && Objects.equals(ref.getNewObjectId(), expectedRef.getNewObjectId())
          && Objects.equals(ref.isFastForward(), expectedRef.isFastForward())
          && Objects.equals(ref.getSrcRef(), expectedRef.getSrcRef())
          && Objects.equals(ref.isForceUpdate(), expectedRef.isForceUpdate())
          && Objects.equals(ref.getMessage(), expectedRef.getMessage());
    }
  }
}
