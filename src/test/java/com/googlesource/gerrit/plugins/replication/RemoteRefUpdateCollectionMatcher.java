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

import java.util.Collection;
import java.util.Objects;
import org.easymock.EasyMock;
import org.easymock.IArgumentMatcher;
import org.eclipse.jgit.transport.RemoteRefUpdate;

public class RemoteRefUpdateCollectionMatcher implements IArgumentMatcher {
  Collection<RemoteRefUpdate> expectedRemoteRefs;

  public static Collection<RemoteRefUpdate> eqRemoteRef(
      Collection<RemoteRefUpdate> expectedRemoteRefs) {
    EasyMock.reportMatcher(new RemoteRefUpdateCollectionMatcher(expectedRemoteRefs));
    return null;
  }

  public RemoteRefUpdateCollectionMatcher(Collection<RemoteRefUpdate> expectedRemoteRefs) {
    this.expectedRemoteRefs = expectedRemoteRefs;
  }

  @Override
  public boolean matches(Object argument) {
    if (!(argument instanceof Collection)) return false;

    @SuppressWarnings("unchecked")
    Collection<RemoteRefUpdate> refs = (Collection<RemoteRefUpdate>) argument;

    if (expectedRemoteRefs.size() != refs.size()) return false;
    return refs.stream()
        .allMatch(
            ref -> expectedRemoteRefs.stream().anyMatch(expectedRef -> compare(ref, expectedRef)));
  }

  @Override
  public void appendTo(StringBuffer buffer) {
    buffer.append("expected:" + expectedRemoteRefs.toString());
  }

  private boolean compare(RemoteRefUpdate ref, RemoteRefUpdate expectedRef) {
    return Objects.equals(ref.getRemoteName(), expectedRef.getRemoteName())
        && Objects.equals(ref.getStatus(), expectedRef.getStatus())
        && Objects.equals(ref.getExpectedOldObjectId(), expectedRef.getExpectedOldObjectId())
        && Objects.equals(ref.getNewObjectId(), expectedRef.getNewObjectId())
        && Objects.equals(ref.isFastForward(), expectedRef.isFastForward())
        && Objects.equals(ref.getSrcRef(), expectedRef.getSrcRef())
        && Objects.equals(ref.isForceUpdate(), expectedRef.isForceUpdate())
        && Objects.equals(ref.getMessage(), expectedRef.getMessage());
  }
}
