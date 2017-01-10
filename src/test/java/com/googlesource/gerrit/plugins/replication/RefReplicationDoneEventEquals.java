// Copyright (C) 2013 The Android Open Source Project
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

import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;

public class RefReplicationDoneEventEquals implements IArgumentMatcher {

  private RefReplicationDoneEvent expected;

  public RefReplicationDoneEventEquals(RefReplicationDoneEvent expected) {
    this.expected = expected;
  }

  public static final RefReplicationDoneEvent eqEvent(RefReplicationDoneEvent refReplicatedEvent) {
    EasyMock.reportMatcher(new RefReplicationDoneEventEquals(refReplicatedEvent));
    return null;
  }

  @Override
  public boolean matches(Object actual) {
    if (!(actual instanceof RefReplicationDoneEvent)) {
      return false;
    }
    RefReplicationDoneEvent actualRefReplicatedDoneEvent = (RefReplicationDoneEvent) actual;
    if (!equals(expected.project, actualRefReplicatedDoneEvent.project)) {
      return false;
    }
    if (!equals(expected.ref, actualRefReplicatedDoneEvent.ref)) {
      return false;
    }
    if (expected.nodesCount != actualRefReplicatedDoneEvent.nodesCount) {
      return false;
    }
    return true;
  }

  private static boolean equals(Object object1, Object object2) {
    if (object1 == object2) {
      return true;
    }
    if (object1 != null && !object1.equals(object2)) {
      return false;
    }
    return true;
  }

  @Override
  public void appendTo(StringBuffer buffer) {
    buffer.append("eqEvent(");
    buffer.append(expected.getClass().getName());
    buffer.append(" with project \"");
    buffer.append(expected.project);
    buffer.append("\" and ref \"");
    buffer.append(expected.ref);
    buffer.append("\" and nodesCount \"");
    buffer.append(expected.nodesCount);
    buffer.append("\")");
  }
}
