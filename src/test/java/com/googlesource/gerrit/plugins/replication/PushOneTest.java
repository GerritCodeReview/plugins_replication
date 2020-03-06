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

import static org.eclipse.jgit.lib.Ref.Storage.NEW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

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

    projectStateMock = mock(ProjectState.class);
    forProjectMock = mock(ForProject.class);
    setupWithUserMock();
    setupPermissionBackedMock();

    setupDestinationMock();

    setupRefSpecMock();
    setupRemoteConfigMock();

    credentialsFactory = mock(CredentialsFactory.class);

    setupFetchConnectionMock();
    setupPushConnectionMock();
    setupRequestScopeMock();
    idGeneratorMock = mock(IdGenerator.class);
    replicationStateListenersMock = mock(ReplicationStateListeners.class);

    timerContextMock = mock(Timer1.Context.class);
    setupReplicationMetricsMock();

    setupTransportMock();

    setupProjectCacheMock();

    replicationConfigMock = mock(ReplicationConfig.class);
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

    when(transportMock.push(any(), eq(expectedUpdates))).thenReturn(pushResult);

    PushOne pushOne = createPushOne(replicationPushFilter);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    isCallFinished.await(TEST_PUSH_TIMEOUT_SECS, TimeUnit.SECONDS);
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

    PushOne pushOne = createPushOne(replicationPushFilter);

    pushOne.addRef(PushOne.ALL_REFS);
    pushOne.run();

    isCallFinished.await(10, TimeUnit.SECONDS);

    verify(transportMock, never()).push(any(), any());
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

  private void setupProjectCacheMock() {
    projectCacheMock = mock(ProjectCache.class);
    when(projectCacheMock.get(projectNameKey)).thenReturn(Optional.of(projectStateMock));
  }

  private void setupTransportMock() throws NotSupportedException, TransportException {
    transportMock = mock(Transport.class);
    when(transportMock.openFetch()).thenReturn(fetchConnection);
    transportFactoryMock = mock(TransportFactory.class);
    when(transportFactoryMock.open(repositoryMock, urish)).thenReturn(transportMock);
  }

  private void setupReplicationMetricsMock() {
    replicationMetricsMock = mock(ReplicationMetrics.class);
    when(replicationMetricsMock.start(any())).thenReturn(timerContextMock);
  }

  private void setupRequestScopeMock() {
    threadRequestScoperMock = mock(PerThreadRequestScope.Scoper.class);
    when(threadRequestScoperMock.scope(any()))
        .thenAnswer(
            new Answer<Callable<Object>>() {
              @SuppressWarnings("unchecked")
              @Override
              public Callable<Object> answer(InvocationOnMock invocation) throws Throwable {
                Callable<Object> originalCall = (Callable<Object>) invocation.getArguments()[0];
                return new Callable<Object>() {

                  @Override
                  public Object call() throws Exception {
                    Object result = originalCall.call();
                    isCallFinished.countDown();
                    return result;
                  }
                };
              }
            });
  }

  private void setupPushConnectionMock() {
    pushConnection = mock(PushConnection.class);
    when(pushConnection.getRefsMap()).thenReturn(remoteRefs);
  }

  private void setupFetchConnectionMock() {
    fetchConnection = mock(FetchConnection.class);
    when(fetchConnection.getRefsMap()).thenReturn(remoteRefs);
  }

  private void setupRemoteConfigMock() {
    remoteConfigMock = mock(RemoteConfig.class);
    when(remoteConfigMock.getPushRefSpecs()).thenReturn(ImmutableList.of(refSpecMock));
  }

  private void setupRefSpecMock() {
    refSpecMock = mock(RefSpec.class);
    when(refSpecMock.matchSource(any(String.class))).thenReturn(true);
    when(refSpecMock.expandFromSource(any(String.class))).thenReturn(refSpecMock);
    when(refSpecMock.getDestination()).thenReturn("fooProject");
    when(refSpecMock.isForceUpdate()).thenReturn(false);
  }

  private void setupDestinationMock() {
    destinationMock = mock(Destination.class);
    when(destinationMock.requestRunway(any())).thenReturn(RunwayStatus.allowed());
  }

  private void setupPermissionBackedMock() {
    permissionBackendMock = mock(PermissionBackend.class);
    when(permissionBackendMock.currentUser()).thenReturn(withUserMock);
  }

  private void setupWithUserMock() {
    withUserMock = mock(WithUser.class);
    when(withUserMock.project(projectNameKey)).thenReturn(forProjectMock);
  }

  private void setupGitRepoManagerMock() throws IOException {
    gitRepositoryManagerMock = mock(GitRepositoryManager.class);
    when(gitRepositoryManagerMock.openRepository(projectNameKey)).thenReturn(repositoryMock);
  }

  private void setupRepositoryMock(FileBasedConfig config) throws IOException {
    repositoryMock = mock(Repository.class);
    refDatabaseMock = mock(RefDatabase.class);
    when(repositoryMock.getConfig()).thenReturn(config);
    when(repositoryMock.getRefDatabase()).thenReturn(refDatabaseMock);
    when(refDatabaseMock.getRefs()).thenReturn(localRefs);
    when(repositoryMock.updateRef("fooProject")).thenReturn(refUpdateMock);
  }

  private void setupRefUpdateMock() {
    refUpdateMock = mock(RefUpdate.class);
    when(refUpdateMock.getOldObjectId())
        .thenReturn(ObjectId.fromString("0000000000000000000000000000000000000001"));
  }
}
