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

public class RefReplicatedEventEquals implements IArgumentMatcher {

  private RefReplicatedEvent expected;

  public RefReplicatedEventEquals(RefReplicatedEvent expected) {
    this.expected = expected;
  }

  public static final RefReplicatedEvent eqEvent(RefReplicatedEvent refReplicatedEvent) {
    EasyMock.reportMatcher(new RefReplicatedEventEquals(refReplicatedEvent));
    return null;
  }

  @Override
  public boolean matches(Object actual) {
    if (!(actual instanceof RefReplicatedEvent)) {
      return false;
    }
    RefReplicatedEvent actualRefReplicatedEvent = (RefReplicatedEvent)actual;
    if (!checkObjectsEquals(expected.project, actualRefReplicatedEvent.project)) {
      return false;
    }
    if (!checkObjectsEquals(expected.ref, actualRefReplicatedEvent.ref)) {
      return false;
    }
    if (!checkObjectsEquals(expected.targetNode, actualRefReplicatedEvent.targetNode)) {
      return false;
    }
    if (!checkObjectsEquals(expected.status, actualRefReplicatedEvent.status)) {
      return false;
    }
    return true;
  }

  private boolean checkObjectsEquals(Object object1, Object object2) {
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
    buffer.append(" and ref \"");
    buffer.append(expected.ref);
    buffer.append(" and targetNode \"");
    buffer.append(expected.targetNode);
    buffer.append(" and status \"");
    buffer.append(expected.status);
    buffer.append("\")");
  }
}
