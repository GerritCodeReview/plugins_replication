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

public class RunwayStatus {
  private static final RunwayStatus ALLOW = new RunwayStatus(true, 0);
  private static final RunwayStatus DENY = new RunwayStatus(false, 0);

  public static RunwayStatus allow() {
    return ALLOW;
  }

  public static RunwayStatus deny() {
    return DENY;
  }

  public static RunwayStatus deny(int inFlightPushId) {
    return new RunwayStatus(false, inFlightPushId);
  }

  public final boolean permissionGiven;
  public final int inFlightPushId;

  private RunwayStatus(boolean permissionGiven, int inFlightPushId) {
    this.permissionGiven = permissionGiven;
    this.inFlightPushId = inFlightPushId;
  }
}
