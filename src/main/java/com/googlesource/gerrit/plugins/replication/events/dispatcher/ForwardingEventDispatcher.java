// Copyright (C) 2026 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.events.dispatcher;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;

public class ForwardingEventDispatcher implements EventDispatcher {
  protected final DynamicItem<com.google.gerrit.server.events.EventDispatcher> delegate;

  @Inject
  public ForwardingEventDispatcher(
      DynamicItem<com.google.gerrit.server.events.EventDispatcher> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void postEvent(BranchNameKey branchName, RefEvent event)
      throws PermissionBackendException {
    delegate.get().postEvent(branchName, event);
  }

  @Override
  public void postEvent(Project.NameKey projectName, ProjectEvent event) {
    delegate.get().postEvent(projectName, event);
  }

  @Override
  public void postEvent(Event event) throws PermissionBackendException {
    delegate.get().postEvent(event);
  }
}
