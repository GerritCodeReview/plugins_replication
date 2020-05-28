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
import static junit.framework.TestCase.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReplicationHttpTest {

  @Mock private StartReplicationRequest startReplicationRequestMock;
  @Mock private HttpServletRequest requestMock;
  @Mock private HttpServletResponse responseMock;

  private EventRestApiServlet servlet;
  private ArgumentCaptor<StartCommandRequest> acStartCommandRequest =
      ArgumentCaptor.forClass(StartCommandRequest.class);
  private StartCommandRequest startCommandRequest=null;

  @Before
  public void setUp() throws Exception {
    servlet = new EventRestApiServlet(startReplicationRequestMock, startCommandRequest);
  }

  @Test
  public void testStartAll() throws IOException {
    String cmd = "all";
    String project = "myproject";
    String url = "gerrit.com";
    String message = messageHelperBase(cmd, project, url) + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
  }

  @Test
  public void testStartProject() throws IOException {
    String cmd = "project";
    String project = "myproject";
    String url = "gerrit.com";

    String message = messageHelperBase(cmd, project, url) + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
  }

  @Test
  public void testStartProjectWait() throws IOException {
    String cmd = "project";
    String project = "myproject";
    String url = "gerrit.com";
    String message =
        messageHelperBase(cmd, project, url)
            + messageHelperBoolean("wait", true)
            + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
    assertTrue(acStartCommandRequest.getValue().isWait());
  }

  @Test
  public void testStartProjectDoNotWait() throws IOException {
    String cmd = "project";
    String project = "myproject";
    String url = "gerrit.com";
    String message =
        messageHelperBase(cmd, project, url)
            + messageHelperBoolean("wait", false)
            + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
    assertFalse(acStartCommandRequest.getValue().isWait());
  }

  @Test
  public void testStartProjectEmptyUrl() throws IOException {
    String cmd = "project";
    String project = "myproject";
    String url = "";
    String message = messageHelperBase(cmd, project, url) + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
  }

  @Test
  public void testStartProjectNoUrl() throws IOException {
    String cmd = "project";
    String project = "myproject";
    String message = "{\"command\":\"" + cmd + "\",\"project\":" + "\"" + project + "\"}";
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertNull(receivedUrl);
  }

  @Test
  public void testStartAllNoUrlNoProject() throws IOException {
    String cmd = "all";
    String message = "{\"command\":\"" + cmd + "\"}";
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertNull(receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertNull(receivedUrl);
  }

  @Test
  public void testStartAllNow() throws IOException {
    String cmd = "all";
    String project = "myproject";
    String url = "gerrit.com";
    String message =
        messageHelperBase(cmd, project, url)
            + messageHelperBoolean("now", true)
            + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
    assertTrue(acStartCommandRequest.getValue().isNow());
  }

  @Test
  public void testStartAllNotNecessarilyNow() throws IOException {
    String cmd = "all";
    String project = "myproject";
    String url = "gerrit.com";
    String message =
        messageHelperBase(cmd, project, url)
            + messageHelperBoolean("now", false)
            + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
    assertFalse(acStartCommandRequest.getValue().isNow());
  }

  @Test
  public void testStartAllNowAndWait() throws IOException {
    String cmd = "all";
    String project = "myproject";
    String url = "gerrit.com";
    String message =
        messageHelperBase(cmd, project, url)
            + messageHelperBoolean("now", true)
            + messageHelperBoolean("wait", true)
            + messageHelperEnding();
    doPostHelper(message);

    String receivedCommand = acStartCommandRequest.getValue().getCommand();
    assertEquals(cmd, receivedCommand);

    String receivedProject = acStartCommandRequest.getValue().getProject();
    assertEquals(project, receivedProject);

    String receivedUrl = acStartCommandRequest.getValue().getUrl();
    assertEquals(url, receivedUrl);
    assertTrue(acStartCommandRequest.getValue().isNow());
    assertTrue(acStartCommandRequest.getValue().isWait());
  }

  @Test
  public void testSendInvalidJson() throws IOException {
    String cmd = "all";
    String message = "{\"command\":\"" + cmd + "\"";

    when(requestMock.getReader()).thenReturn(new BufferedReader(new StringReader(message)));
    servlet.doPost(requestMock, responseMock);
    verify(responseMock).sendError(SC_BAD_REQUEST);
  }

  @Test
  public void testSendNullCommand() throws IOException {
    String cmd = "all";
    String message = "{\"bad_cmd\":\"" + cmd + "\"}";

    when(requestMock.getReader()).thenReturn(new BufferedReader(new StringReader(message)));
    servlet.doPost(requestMock, responseMock);
    verify(responseMock).sendError(SC_BAD_REQUEST);
  }

  private String messageHelperBase(String cmd, String project, String url) {
    return "{\"command\":\""
        + cmd
        + "\",\"project\":"
        + "\""
        + project
        + "\",\"url\":\""
        + url
        + "\"";
  }

  private String messageHelperBoolean(String parameter, boolean value) {
    return ",\"" + parameter + "\":" + value;
  }

  private String messageHelperEnding() {
    return "}";
  }

  private void doPostHelper(String Message) throws IOException {
    when(requestMock.getReader()).thenReturn(new BufferedReader(new StringReader(Message)));
    servlet.doPost(requestMock, responseMock);
    verify(startReplicationRequestMock)
        .execute(acStartCommandRequest.capture(), any(HttpServletResponse.class));
  }
}
