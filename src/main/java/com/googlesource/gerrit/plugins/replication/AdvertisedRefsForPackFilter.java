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

package com.googlesource.gerrit.plugins.replication;

import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RemoteRefUpdate;

class AdvertisedRefsForPackFilter {
  interface Factory {
    AdvertisedRefsForPackFilter create(
        Repository git, Project.NameKey project, Collection<RemoteRefUpdate> updates);
  }

  protected final ChangeNotes.Factory changeNotesFactory;
  protected final Project.NameKey project;
  protected final Repository git;
  protected final Collection<RemoteRefUpdate> updates;
  protected Collection<Ref> advertisedRefs;
  protected Map<ObjectId, Ref> advertisedRefByObjectId = new HashMap<>();
  protected NavigableMap<String, Ref> advertisedRefsByName = new TreeMap<>();
  protected Set<String> toPushRefNames;
  protected Set<Ref> neededRefs;

  @Inject
  AdvertisedRefsForPackFilter(
      ChangeNotes.Factory changeNotesFactory,
      @Assisted Repository git,
      @Assisted Project.NameKey project,
      @Assisted Collection<RemoteRefUpdate> updates) {
    this.changeNotesFactory = changeNotesFactory;
    this.git = git;
    this.project = project;
    this.updates = updates;
  }

  Collection<Ref> filter(Collection<Ref> advertisedRefs) {
    this.advertisedRefs = advertisedRefs;
    buildAdvertisedRefsMaps();
    toPushRefNames =
        updates.stream().map(RemoteRefUpdate::getRemoteName).collect(Collectors.toSet());
    neededRefs =
        advertisedRefs.stream()
            .filter(ref -> toPushRefNames.contains(ref.getName()))
            .collect(Collectors.toSet());

    Set<RemoteRefUpdate> hasNoShortcut = new HashSet<>();
    for (RemoteRefUpdate toPush : updates) {
      ObjectId newObjectId = toPush.getNewObjectId();
      if (newObjectId == null || toPush.isDelete()) {
        continue; // No optimizations needed for deletes
      }
      if (!hasExistingTipShortCut(newObjectId)) {
        Set<ObjectId> parents = getParents(toPush);
        if (!hasChildOfExistingTipShortCut(parents)
            && !hasChangeDestinationShortCut(toPush, parents)) {
          hasNoShortcut.add(toPush);
        }
      }
    }

    // A push which avoids the 'if' below will at most push a single commit per ref update.
    // It is possible that pushing even one commit could be more than was necessary if that
    // commit were already on another ref on the remote. The larger the commit, the more
    // impactful this could be. For perspective, we recently saw a 28GB commit being pushed
    // (via a 4GB packfile)! Since normal change refs only contain a single commit not on
    // another change, or another ref, this situation is possible only if that commit is on
    // a ref which is not a normal change ref.
    //
    // Note: a "normal" change ref has a single parent, and was not pushed with special flags
    // preventing parent changes from being created, and it has not had its destination ref
    // rewound to remove its parent.
    if (!hasNoShortcut.isEmpty()) {
      // A ref with no shortcut must be pushing more than one commit, so there is no upper
      // bound on how large this push could be. Such pushes could push the entire history
      // of the ref being pushed if no merge-bases can be identified on another remote ref.

      // New refs whose non tip commits point to commits which contain changes which contain
      // commits not on other refs (which can only be unmerged or non-normal changes), could get
      // slower when change refs are filtered. These are not normal situations and are not likely
      // worth caring about?
      neededRefs.addAll(
          advertisedRefs.stream()
              .filter(
                  r ->
                      !RefNames.isRefsChanges(r.getName()) && !RefNames.isAutoMergeRef(r.getName()))
              .collect(Collectors.toSet()));
    }

    return neededRefs;
  }

  protected boolean hasExistingTipShortCut(ObjectId newObjectId) {
    Ref ref = advertisedRefByObjectId.get(newObjectId);
    if (ref == null) {
      return false;
    }
    neededRefs.add(ref);
    return true;
  }

  protected boolean hasChildOfExistingTipShortCut(@Nullable Set<ObjectId> parents) {
    if (parents == null || parents.isEmpty()) {
      return false;
    }
    boolean allParentsAdvertised = true;
    for (ObjectId parentId : parents) {
      Ref parentRef = advertisedRefByObjectId.get(parentId);
      if (parentRef == null) {
        allParentsAdvertised = false;
      } else {
        neededRefs.add(parentRef);
      }
    }
    return allParentsAdvertised;
  }

  protected boolean hasChangeDestinationShortCut(
      RemoteRefUpdate toPush, @Nullable Set<ObjectId> parents) {
    String name = toPush.getRemoteName();
    if (PatchSet.Id.fromRef(name) == null) {
      return false;
    }
    // TODO: actually do the revwalk to ensure we have a merge-base
    boolean hasChangeDestShortCut = false;
    Change.Id changeId = Change.Id.fromRef(name);
    try {
      ChangeNotes notes = changeNotesFactory.create(git, project, changeId);
      Ref changeDest = advertisedRefsByName.get(notes.getChange().getDest().branch());
      if (changeDest != null) {
        neededRefs.add(changeDest);
        hasChangeDestShortCut = true;
      }
    } catch (StorageException e) {
      repLog.atFine().withCause(e).log(
          "Could not read change metadata for %s to add destination ref", changeId);
    }
    return hasChangeDestShortCut && parents != null && parents.size() == 1;
  }

  @Nullable
  private Set<ObjectId> getParents(RemoteRefUpdate update) {
    try {
      ObjectId objectId = update.getNewObjectId();
      if (objectId == null || ObjectId.zeroId().equals(objectId)) {
        return Collections.emptySet();
      }
      Set<ObjectId> parents = new HashSet<>();
      try (RevWalk rw = new RevWalk(git)) {
        RevObject obj = rw.parseAny(objectId);
        if (obj instanceof RevTag) {
          obj = rw.peel(obj);
        }
        if (obj instanceof RevCommit) {
          RevCommit c = rw.parseCommit(objectId);
          for (int p = 0; p < c.getParentCount(); p++) {
            parents.add(c.getParent(p));
          }
        }
      }
      return parents;
    } catch (IOException e) {
      repLog.atWarning().withCause(e).log("Unable to get parents of %s", update.getRemoteName());
      return null;
    }
  }

  private void buildAdvertisedRefsMaps() {
    for (Ref ref : advertisedRefs) {
      ObjectId oid = ref.getObjectId();
      if (oid != null) {
        advertisedRefByObjectId.put(oid, ref);
      }
      advertisedRefsByName.put(ref.getName(), ref);
    }
  }
}
