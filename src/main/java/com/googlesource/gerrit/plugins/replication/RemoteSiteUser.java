// Copyright (C) 2009 The Android Open Source Project
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

import com.google.gerrit.reviewdb.client.AccountProjectWatch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.CapabilityControl;
import com.google.gerrit.server.account.GroupMembership;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

class RemoteSiteUser extends CurrentUser {
  interface Factory {
    RemoteSiteUser create(@Assisted GroupMembership authGroups);
  }

  private final GroupMembership effectiveGroups;

  @Inject
  RemoteSiteUser(CapabilityControl.Factory capabilityControlFactory,
      @Assisted GroupMembership authGroups) {
    super(capabilityControlFactory, AccessPath.GIT);
    effectiveGroups = authGroups;
  }

  @Override
  public GroupMembership getEffectiveGroups() {
    return effectiveGroups;
  }

  @Override
  public Set<Change.Id> getStarredChanges() {
    return Collections.emptySet();
  }

  @Override
  public Collection<AccountProjectWatch> getNotificationFilters() {
    return Collections.emptySet();
  }
}
