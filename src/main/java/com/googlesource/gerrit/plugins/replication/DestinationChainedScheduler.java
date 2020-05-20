// Copyright (C) 2020 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.googlesource.gerrit.plugins.replication.PushResultProcessing.GitUpdateProcessing;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.transport.URIish;

public class DestinationChainedScheduler {
  public static class UriPush {
    public final URIish uri;
    public final Project.NameKey project;
    public final Set<String> refs;

    public UriPush(URIish uri, Project.NameKey project, String ref, Set<String> oldRefs) {
      this.uri = uri;
      this.project = project;

      if (oldRefs != null) {
        Set<String> all = new HashSet<>(oldRefs);
        all.add(ref);
        refs = Collections.unmodifiableSet(all);
      } else {
        refs = Collections.singleton(ref);
      }
    }
  }

  private final Destination destination;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  private final AtomicBoolean needsScheduling = new AtomicBoolean();
  private final AtomicBoolean scheduling = new AtomicBoolean();
  private final Map<URIish, UriPush> toSchedule = new ConcurrentHashMap<>();

  protected DestinationChainedScheduler(
      Destination destination, DynamicItem<EventDispatcher> eventDispatcher) {
    this.eventDispatcher = eventDispatcher;
    this.destination = destination;
  }

  public void toSchedule(Project.NameKey project, String ref, URIish uri) {
    if (uri != null) {
      UriPush old = toSchedule.get(uri);
      while (!toScheduleReplace(
          uri, old, new UriPush(uri, project, ref, old == null ? null : old.refs))) {
        old = toSchedule.get(uri);
      }
      schedule(true);
    }
  }

  /* Needed because <explitive> concurrentHashMap does not allow null for oldValue */
  private boolean toScheduleReplace(URIish uri, UriPush oldValue, UriPush newValue) {
    if (oldValue != null) {
      return !toSchedule.replace(uri, oldValue, newValue);
    }
    return toSchedule.putIfAbsent(uri, newValue) == null;
  }

  private void schedule(boolean added) {
    if (added) {
      needsScheduling.set(true);
    }
    if (!needsScheduling.compareAndSet(true, false)) {
      return;
    }
    if (!scheduling.compareAndSet(false, true)) {
      needsScheduling.set(true);
      return;
    }
    final Collection<UriPush> values = toSchedule.values();
    new ChainedScheduler<UriPush>(
        destination.getScheduledExecutorService(),
        values.iterator(),
        new ChainedScheduler.Runner<UriPush>() {
          @Override
          public void run(UriPush uriPush) {
            for (String ref : uriPush.refs) {
              destination.schedule(
                  uriPush.project,
                  ref,
                  uriPush.uri,
                  new ReplicationState(new GitUpdateProcessing(eventDispatcher.get())),
                  true);
            }
          }

          @Override
          public void onDone() {
            scheduling.set(false);
            schedule(false);
          }

          @Override
          public String toString(UriPush uriPush) {
            return "Scheduling push to " + uriPush.uri;
          }
        },
        true);
  }
}
