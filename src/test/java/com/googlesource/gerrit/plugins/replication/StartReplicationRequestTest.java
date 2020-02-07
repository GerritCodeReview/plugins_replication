// Copyright (C) 2020 The Android Open Source Project
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

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StartReplicationRequestTest {

  @Mock private PushAll.Factory pushFactoryMock;
  @Mock private PushAll pushAllMock;
  @Mock private HttpServletResponse responseMock;

  private StartReplicationRequest startReplicationRequest;
  private ArgumentCaptor<String> acUrlMatch = ArgumentCaptor.forClass(String.class);
  private ArgumentCaptor<ReplicationFilter> acReplicationFilter =
      ArgumentCaptor.forClass(ReplicationFilter.class);
  private Project.NameKey projectNameKey;
  private Project.NameKey projectNameKey_alt;

  @Before
  public void setUp() throws Exception {
    startReplicationRequest = new StartReplicationRequest(pushFactoryMock);
  }

  @Test
  public void testStartAll() throws IOException {
    String cmd = "all";
    String project = "anyproject";
    String urlMatch = "gerrit.com";

    setUpMocks();
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch);
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).setStatus(SC_NO_CONTENT);
    assertEquals(urlMatch, acUrlMatch.getValue());
    ReplicationFilter replicationFilter = acReplicationFilter.getValue();
    projectNameKey = Project.nameKey(project);
    assertTrue(replicationFilter.matches(projectNameKey));
    projectNameKey_alt = Project.nameKey("anotherProject");
    assertTrue(replicationFilter.matches(projectNameKey_alt));
  }

  @Test
  public void testStartSingleProject() throws IOException {
    String cmd = "project";
    String project = "anyproject";
    String urlMatch = "gerrit.com";
    String projectAlt = "anotherproject";

    setUpMocks();
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch);
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).setStatus(SC_NO_CONTENT);
    assertEquals(urlMatch, acUrlMatch.getValue());
    ReplicationFilter replicationFilter = acReplicationFilter.getValue();
    projectNameKey = Project.nameKey(project);
    assertTrue(replicationFilter.matches(projectNameKey));
    projectNameKey_alt = Project.nameKey(projectAlt);
    assertFalse(replicationFilter.matches(projectNameKey_alt));
  }

  @Test
  public void testStartSingleProjectDoNotWait() throws IOException {
    String cmd = "project";
    String project = "anyproject";
    String urlMatch = "gerrit.com";
    String projectAlt = "anotherproject";

    setUpMocks();
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch, true, false);
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).setStatus(SC_NO_CONTENT);
    assertEquals(urlMatch, acUrlMatch.getValue());
    ReplicationFilter replicationFilter = acReplicationFilter.getValue();
    projectNameKey = Project.nameKey(project);
    assertTrue(replicationFilter.matches(projectNameKey));
    projectNameKey_alt = Project.nameKey(projectAlt);
    assertFalse(replicationFilter.matches(projectNameKey_alt));
  }

  @Test
  public void testStartSingleProjectNoUrl() throws IOException {
    String cmd = "project";
    String project = "anyproject";
    String projectAlt = "anotherproject";

    setUpMocks();
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project);
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).setStatus(SC_NO_CONTENT);
    assertNull(acUrlMatch.getValue());
    ReplicationFilter replicationFilter = acReplicationFilter.getValue();
    projectNameKey = Project.nameKey(project);
    assertTrue(replicationFilter.matches(projectNameKey));
    projectNameKey_alt = Project.nameKey(projectAlt);
    assertFalse(replicationFilter.matches(projectNameKey_alt));
  }

  @Test
  public void testStartSingleProjectWaitAndNowNullFuture() throws IOException {
    String cmd = "project";
    String project = "anyproject";
    String urlMatch = "gerrit2.com";
    String projectAlt = "anotherproject";

    setUpMocks();
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch, true, true);
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).sendError(SC_INTERNAL_SERVER_ERROR, "Nothing to replicate");
    assertEquals(urlMatch, acUrlMatch.getValue());
    ReplicationFilter replicationFilter = acReplicationFilter.getValue();
    projectNameKey = Project.nameKey(project);
    assertTrue(replicationFilter.matches(projectNameKey));
    projectNameKey_alt = Project.nameKey(projectAlt);
    assertFalse(replicationFilter.matches(projectNameKey_alt));
  }

  @Test
  public void testStartSingleProjectWaitAndStop() throws IOException {
    String cmd = "all";
    String project = "anyproject2";
    String urlMatch = "gerrit-dev.com";

    final ReplicationStateLogger replicationStateLoggerMock =
        Mockito.mock(ReplicationStateLogger.class);
    setUpMocksWithFutureAndException(InterruptedException.class);
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch, true, false);
    startReplicationRequest.execute(StartCommandRequest, responseMock, replicationStateLoggerMock);
    verify(replicationStateLoggerMock)
        .error(
            Mockito.contains(
                "Thread was interrupted while waiting for PushAll operation to finish"),
            any(InterruptedException.class),
            any(ReplicationState.class));
  }

  @Test
  public void testStartSingleProjectWaitAndException() throws IOException {
    String cmd = "project";
    String project = "anyproject";
    String urlMatch = "gerrit-dev.com";

    final ReplicationStateLogger replicationStateLoggerMock =
        Mockito.mock(ReplicationStateLogger.class);
    setUpMocksWithFutureAndException(ExecutionException.class);
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, urlMatch, true, false);
    startReplicationRequest.execute(StartCommandRequest, responseMock, replicationStateLoggerMock);
    verify(replicationStateLoggerMock)
        .error(
            Mockito.contains("An exception was thrown in PushAll operation"),
            any(ExecutionException.class),
            any(ReplicationState.class));
  }

  @Test
  public void testStartBadCommand() throws IOException {
    String SC_BAD_REQUEST_MESG = "Invalid Command";
    StartCommandRequest StartCommandRequest = createStartCommandRequest("allall", "dummy", "gerrit.com");
    startReplicationRequest.execute(StartCommandRequest, responseMock);
    verify(responseMock).sendError(SC_BAD_REQUEST, SC_BAD_REQUEST_MESG);
  }

  private void setUpMocks() {
    when(pushFactoryMock.create(
            acUrlMatch.capture(),
            acReplicationFilter.capture(),
            any(ReplicationState.class),
            anyBoolean()))
        .thenReturn(pushAllMock);
    when(pushAllMock.schedule(anyLong(), any(TimeUnit.class))).thenReturn(null);
  }


  @SuppressWarnings("unchecked")
private <T extends Throwable> void setUpMocksWithFutureAndException(Class<T> exceptionType) {
    @SuppressWarnings("rawtypes")
	final Future mockedFuture = Mockito.mock(Future.class);

    when(pushFactoryMock.create(
            acUrlMatch.capture(),
            acReplicationFilter.capture(),
            any(ReplicationState.class),
            anyBoolean()))
        .thenReturn(pushAllMock);
    when(pushAllMock.schedule(anyLong(), any(TimeUnit.class))).thenReturn(mockedFuture);

    if (exceptionType.getSimpleName().equals("InterruptedException")) {
      try {
        when(mockedFuture.get()).thenThrow(InterruptedException.class);
      } catch (Exception ignore) {
      }
    }
    if (exceptionType.getSimpleName().equals("ExecutionException")) {
      try {
        when(mockedFuture.get()).thenThrow(ExecutionException.class);
      } catch (Exception ignore) {
      }
    }
  }

  private StartCommandRequest createStartCommandRequest(String cmd, String project) {
    StartCommandRequest StartCommandRequest = new StartCommandRequest();
    StartCommandRequest.setCommand(cmd);
    StartCommandRequest.setProject(project);
    return StartCommandRequest;
  }

  private StartCommandRequest createStartCommandRequest(String cmd, String project, String url) {
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project);
    StartCommandRequest.setUrl(url);
    return StartCommandRequest;
  }

  private StartCommandRequest createStartCommandRequest(
      String cmd, String project, String url, boolean wait, boolean now) {
    StartCommandRequest StartCommandRequest = createStartCommandRequest(cmd, project, url);
    StartCommandRequest.setWait(wait);
    StartCommandRequest.setNow(now);
    return StartCommandRequest;
  }
}
