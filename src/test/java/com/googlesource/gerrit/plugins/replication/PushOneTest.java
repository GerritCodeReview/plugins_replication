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

import static com.googlesource.gerrit.plugins.replication.RemoteRefUpdateCollectionMatcher.eqRemoteRef;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.eclipse.jgit.lib.Ref.Storage.NEW;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import junit.framework.AssertionFailedError;
import org.easymock.IAnswer;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
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
  private static final int TEST_PUSH_TIMEOUT_SECS = 10;

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
  private IdGenerator idGeneratorMock;
  private ReplicationStateListeners replicationStateListenersMock;
  private ReplicationMetrics replicationMetricsMock;
  private Timer1.Context<String> timerContextMock;
  private ProjectCache projectCacheMock;
  private TransportFactory transportFactoryMock;
  private Transport transportMock;
  private FetchConnection fetchConnection;
  private PushConnection pushConnection;
  private ProjectState projectStateMock;
  private RefUpdate refUpdateMock;
  private CreateProjectTask.Factory createProjectTaskFactoryMock;
  private ReplicationConfig replicationConfigMock;
  private RefDatabase refDatabaseMock;

  private Project.NameKey projectNameKey;
  private URIish urish;
  private List<Ref> localRefs;

  private Map<String, Ref> remoteRefs;
  private CountDownLatch isCallFinished;
  private Ref newLocalRef;

  @Before
  public void setup() throws Exception {
    projectNameKey = Project.nameKey("fooProject");
    urish = new URIish("http://foo.com/fooProject.git");

    newLocalRef =
        new ObjectIdRef.Unpeeled(
            NEW, "foo", ObjectId.fromString("0000000000000000000000000000000000000001"));

    localRefs = Arrays.asList(newLocalRef);

    Ref remoteRef = new ObjectIdRef.Unpeeled(NEW, "foo", ObjectId.zeroId());
    remoteRefs = new HashMap<>();
    remoteRefs.put("fooProject", remoteRef);

    isCallFinished = new CountDownLatch(1);

    setupMocks();
  }

  @SuppressWarnings("unchecked")
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
    idGeneratorMock = createNiceMock(IdGenerator.class);
    replicationStateListenersMock = createNiceMock(ReplicationStateListeners.class);

    timerContextMock = createNiceMock(Timer1.Context.class);
    setupReplicationMetricsMock();

    setupTransportMock();

    setupProjectCacheMock();

    replicationConfigMock = createNiceMock(ReplicationConfig.class);

    replay(
        gitRepositoryManagerMock,
        refUpdateMock,
        repositoryMock,
        permissionBackendMock,
        destinationMock,
        remoteConfigMock,
        credentialsFactory,
        threadRequestScoperMock,
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
        refSpecMock,
        refDatabaseMock,
        replicationConfigMock);
  }

  @Test
  public void shouldPushAllRefsWhenNoFilters() throws InterruptedException, IOException {
    shouldPushAllRefsWithDynamicItemFilter(DynamicItem.itemOf(ReplicationPushFilter.class, null));
  }

  @Test
  public void shouldPushAllRefsWhenNoFiltersSetup() throws InterruptedException, IOException {
    shouldPushAllRefsWithDynamicItemFilter(null);
  }

  private void shouldPushAllRefsWithDynamicItemFilter(
      DynamicItem<ReplicationPushFilter> replicationPushFilter)
      throws IOException, NotSupportedException, TransportException, InterruptedException {
    List<RemoteRefUpdate> expectedUpdates =
        Arrays.asList(
            new RemoteRefUpdate(
                repositoryMock,
                newLocalRef.getName(),
                newLocalRef.getObjectId(),
                "fooProject",
                false,
                "fooProject",
                null));

    PushResult pushResult = new PushResult();

    expect(transportMock.push(anyObject(), eqRemoteRef(expectedUpdates)))
        .andReturn(pushResult)
        .once();
    replay(transportMock);

    PushOne pushOne = createPushOne(replicationPushFilter);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    isCallFinished.await(TEST_PUSH_TIMEOUT_SECS, TimeUnit.SECONDS);

    verify(transportMock);
  }

  @Test
  public void shouldBlockReplicationUsingPushFilter() throws InterruptedException, IOException {
    DynamicItem<ReplicationPushFilter> replicationPushFilter =
        DynamicItem.itemOf(
            ReplicationPushFilter.class,
            new ReplicationPushFilter() {

              @Override
              public List<RemoteRefUpdate> filter(
                  String projectName, List<RemoteRefUpdate> remoteUpdatesList) {
                return Collections.emptyList();
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

    isCallFinished.await(10, TimeUnit.SECONDS);

    verify(transportMock);
  }

  private PushOne createPushOne(DynamicItem<ReplicationPushFilter> replicationPushFilter) {
    PushOne push =
        new PushOne(
            gitRepositoryManagerMock,
            permissionBackendMock,
            destinationMock,
            remoteConfigMock,
            replicationConfigMock,
            credentialsFactory,
            threadRequestScoperMock,
            idGeneratorMock,
            replicationStateListenersMock,
            replicationMetricsMock,
            projectCacheMock,
            createProjectTaskFactoryMock,
            transportFactoryMock,
            projectNameKey,
            urish);

    push.setReplicationPushFilter(replicationPushFilter);
    return push;
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
                    isCallFinished.countDown();
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
    refDatabaseMock = createNiceMock(RefDatabase.class);
    expect(repositoryMock.getConfig()).andReturn(config).anyTimes();
    expect(repositoryMock.getRefDatabase()).andReturn(refDatabaseMock);
    expect(refDatabaseMock.getRefs()).andReturn(localRefs);
    expect(repositoryMock.updateRef("fooProject")).andReturn(refUpdateMock);
  }

  private void setupRefUpdateMock() {
    refUpdateMock = createNiceMock(RefUpdate.class);
    expect(refUpdateMock.getOldObjectId())
        .andReturn(ObjectId.fromString("0000000000000000000000000000000000000001"))
        .anyTimes();
  }
}
