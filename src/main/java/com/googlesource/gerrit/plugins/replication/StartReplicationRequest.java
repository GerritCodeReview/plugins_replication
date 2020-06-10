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

import com.googlesource.gerrit.plugins.replication.StartReplicationProcessing.CommandType;

public class StartReplicationRequest {
  private String command;
  private String project;
  private String url;
  private boolean now;
  private boolean wait;

  public boolean isWait() {
    return wait;
  }

  public void setWait(boolean wait) {
    this.wait = wait;
  }

  public boolean isNow() {
    return now;
  }

  public void setNow(boolean now) {
    this.now = now;
  }

  public void setCommand(CommandType command) {
    this.command = command.toString().toLowerCase();
  }

  public String getProject() {
    return project;
  }

  public void setProject(String project) {
    this.project = project;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public CommandType getCommand() {
    if (this.command == null) {
      throw new IllegalArgumentException();
    }
    return CommandType.valueOf(this.command.toUpperCase());
  }
}
