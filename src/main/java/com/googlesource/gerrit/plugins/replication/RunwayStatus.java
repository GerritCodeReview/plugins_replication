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
  public static RunwayStatus allowed() {
    return new RunwayStatus(true, 0);
  }

  public static RunwayStatus canceled() {
    return new RunwayStatus(false, 0);
  }

  public static RunwayStatus denied(int inFlightPushId) {
    return new RunwayStatus(false, inFlightPushId);
  }

  public static RunwayStatus deniedExternal() {
    return new RunwayStatus(false, -1);
  }

  private final boolean allowed;
  private final int inFlightPushId;

  private RunwayStatus(boolean allowed, int inFlightPushId) {
    this.allowed = allowed;
    this.inFlightPushId = inFlightPushId;
  }

  public boolean isAllowed() {
    return allowed;
  }

  public boolean isCanceled() {
    return !allowed && inFlightPushId == 0;
  }

  public boolean isExternalInflight() {
    return !allowed && inFlightPushId == -1;
  }

  public int getInFlightPushId() {
    return inFlightPushId;
  }
}
