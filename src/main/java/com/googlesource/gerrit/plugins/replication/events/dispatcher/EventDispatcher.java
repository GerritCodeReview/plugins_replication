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
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.permissions.PermissionBackendException;

/**
 * Plugin-local indirection over {@link com.google.gerrit.server.events.EventDispatcher}, so that
 * event emission can be turned off entirely via the {@code replication.emitEvents} config option.
 */
public interface EventDispatcher {

  void postEvent(BranchNameKey branchName, RefEvent event) throws PermissionBackendException;

  void postEvent(Project.NameKey projectName, ProjectEvent event);

  void postEvent(Event event) throws PermissionBackendException;
}
