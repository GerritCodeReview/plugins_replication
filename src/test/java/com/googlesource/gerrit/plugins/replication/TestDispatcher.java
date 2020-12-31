// Copyright (C) 2021 The Android Open Source Project
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

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.ChangeEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefEvent;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class TestDispatcher implements EventDispatcher {

  private final List<ProjectEvent> projectEvents = new LinkedList<>();

  @Override
  public void postEvent(Change change, ChangeEvent event) {}

  @Override
  public void postEvent(BranchNameKey branchName, RefEvent event) {}

  @Override
  public void postEvent(Project.NameKey projectName, ProjectEvent event) {
    projectEvents.add(event);
  }

  @Override
  public void postEvent(Event event) {}

  private List<ProjectEvent> getEventsForProject(String projectName) {
    return projectEvents.stream()
        .filter(e -> e.getProjectNameKey().get().equals(projectName))
        .collect(Collectors.toList());
  }

  public List<ProjectEvent> getMatching(String projectName, Class<? extends ProjectEvent> clazz) {
    return getEventsForProject(projectName).stream()
        .filter(clazz::isInstance)
        .collect(Collectors.toList());
  }

  public String projectEventsString(String project) {
    return getEventsForProject(project).stream()
        .map(e -> String.format("(%s [created: %d])", e.type, e.eventCreatedOn))
        .collect(Collectors.joining(","));
  }
}
