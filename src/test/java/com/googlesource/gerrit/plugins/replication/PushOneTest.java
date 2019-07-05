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
import static org.eclipse.jgit.lib.Ref.Storage.NEW;

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
import com.google.gwt.dev.util.collect.HashMap;
import java.io.File;
import java.util.Map;
import java.util.concurrent.Callable;
import org.easymock.IAnswer;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;

public class PushOneTest {
  GitRepositoryManager gitRepositoryManagerMock;
  Repository repositoryMock;
  PermissionBackend permissionBackendMock;
  PermissionBackend.WithUser withUserMock;
  PermissionBackend.ForProject forProjectMock;

  Destination destinationMock;
  RemoteConfig remoteConfigMock;
  CredentialsFactory credentialsFactory;
  PerThreadRequestScope.Scoper threadRequestScoperMock;
  ReplicationQueue replicationQueueMock;
  IdGenerator idGeneratorMock;
  ReplicationStateListeners replicationStateListenersMock;
  ReplicationMetrics replicationMetricsMock;
  Timer1.Context timerContextMock;
  ProjectCache projectCacheMock;
  RunwayStatus statusMock;
  TransportFactory transportFactoryMock;
  FakeTransport fakeTransport;
  ProjectState projectStateMock;

  DynamicItem<ReplicationPushFilter> replicationPushFilter;
  Project.NameKey projectNameKey;
  URIish urish;
  Map<String, Ref> allRefs;

  @Before
  public void setup() throws Exception {
    projectNameKey = new Project.NameKey("fooProject");
    urish = new URIish("http://foo.com/fooProject.git");
    fakeTransport = new FakeTransport();
    allRefs = new HashMap<>();
    allRefs.put("foo", new ObjectIdRef.Unpeeled(NEW, "foo", null));

    setupMocks();
  }

  @SuppressWarnings("unchecked")
  private void setupMocks() throws Exception {
    gitRepositoryManagerMock = createNiceMock(GitRepositoryManager.class);
    repositoryMock = createNiceMock(Repository.class);
    expect(repositoryMock.getConfig())
        .andReturn(new FileBasedConfig(new Config(), new File("/foo"), FS.DETECTED))
        .anyTimes();
    expect(repositoryMock.getAllRefs()).andReturn(allRefs);
    expect(gitRepositoryManagerMock.openRepository(projectNameKey)).andReturn(repositoryMock);
    permissionBackendMock = createNiceMock(PermissionBackend.class);
    withUserMock = createNiceMock(WithUser.class);
    expect(permissionBackendMock.currentUser()).andReturn(withUserMock);
    forProjectMock = createNiceMock(ForProject.class);
    expect(withUserMock.project(projectNameKey)).andReturn(forProjectMock);
    destinationMock = createNiceMock(Destination.class);
    statusMock = createNiceMock(RunwayStatus.class);
    expect(destinationMock.requestRunway(anyObject())).andReturn(RunwayStatus.allowed());
    remoteConfigMock = createNiceMock(RemoteConfig.class);
    credentialsFactory = createNiceMock(CredentialsFactory.class);
    threadRequestScoperMock = createNiceMock(PerThreadRequestScope.Scoper.class);
    expect(threadRequestScoperMock.scope(anyObject()))
        .andAnswer(
            new IAnswer<Callable<Object>>() {
              @Override
              public Callable<Object> answer() throws Throwable {
                return (Callable<Object>) getCurrentArguments()[0];
              }
            })
        .anyTimes();
    replicationQueueMock = createNiceMock(ReplicationQueue.class);
    idGeneratorMock = createNiceMock(IdGenerator.class);
    replicationStateListenersMock = createNiceMock(ReplicationStateListeners.class);
    replicationMetricsMock = createNiceMock(ReplicationMetrics.class);
    timerContextMock = createNiceMock(Timer1.Context.class);
    expect(replicationMetricsMock.start(anyObject())).andReturn(timerContextMock);
    projectCacheMock = createNiceMock(ProjectCache.class);
    transportFactoryMock = createNiceMock(TransportFactory.class);
    expect(transportFactoryMock.open(repositoryMock, urish)).andReturn(fakeTransport).anyTimes();
    projectStateMock = createNiceMock(ProjectState.class);
    expect(projectCacheMock.checkedGet(projectNameKey)).andReturn(projectStateMock);

    replay(
        gitRepositoryManagerMock,
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
        forProjectMock);
  }

  @Test
  public void shouldPushAllRefs() {
    DynamicItem<ReplicationPushFilter> replicationPushFilter =
        DynamicItem.itemOf(ReplicationPushFilter.class, null);
    PushOne pushOne =
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
            replicationPushFilter,
            transportFactoryMock,
            projectNameKey,
            urish);
    pushOne.run();
  };
}
