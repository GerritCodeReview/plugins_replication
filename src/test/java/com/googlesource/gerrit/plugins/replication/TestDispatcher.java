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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TestDispatcher implements EventDispatcher {
  private final List<ProjectEvent> projectEvents = new ArrayList<>();
  private final List<RefEvent> refEvents = new ArrayList<>();
  private final List<Event> events = new ArrayList<>();

  @Override
  public void postEvent(Change change, ChangeEvent event) {} // Not used in replication

  @Override
  public void postEvent(BranchNameKey branchName, RefEvent event) {
    refEvents.add(event);
  }

  @Override
  public void postEvent(Project.NameKey projectName, ProjectEvent event) {
    projectEvents.add(event);
  }

  @Override
  public void postEvent(Event event) {
    events.add(event);
  }

  public List<RefEvent> getEvents(BranchNameKey branch, Class<? extends RefEvent> clazz) {
    return getEvents(branch).stream().filter(clazz::isInstance).collect(Collectors.toList());
  }

  public <T extends ProjectEvent> List<T> getEvents(Project.NameKey project, Class<T> clazz) {
    return getEvents(project).stream()
        .filter(clazz::isInstance)
        .map(clazz::cast)
        .collect(Collectors.toList());
  }

  public <T extends RefEvent> List<T> getEvents(Class<T> clazz) {
    return events.stream().filter(clazz::isInstance).map(clazz::cast).collect(Collectors.toList());
  }

  private List<RefEvent> getEvents(BranchNameKey branch) {
    return refEvents.stream()
        .filter(e -> e.getBranchNameKey().equals(branch))
        .collect(Collectors.toList());
  }

  private List<ProjectEvent> getEvents(Project.NameKey project) {
    return projectEvents.stream()
        .filter(e -> e.getProjectNameKey().equals(project))
        .collect(Collectors.toList());
  }
}
