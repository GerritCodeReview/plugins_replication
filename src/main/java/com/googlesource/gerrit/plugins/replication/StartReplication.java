// Copyright (C) 2018 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.deleteproject.DeleteOwnProjectCapability.DELETE_OWN_PROJECT;
import static com.googlesource.gerrit.plugins.deleteproject.DeleteProjectCapability.DELETE_PROJECT;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllProjectsNameProvider;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.notedb.NotesMigration;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.deleteproject.DeleteProject.Input;
import com.googlesource.gerrit.plugins.deleteproject.cache.CacheDeleteHandler;
import com.googlesource.gerrit.plugins.deleteproject.database.DatabaseDeleteHandler;
import com.googlesource.gerrit.plugins.deleteproject.fs.FilesystemDeleteHandler;
import com.googlesource.gerrit.plugins.deleteproject.projectconfig.ProjectConfigDeleteHandler;
import java.io.IOException;
import java.util.Collection;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

class StartReplication implements RestModifyView<ProjectResource, Input> {
  static class Input {
    boolean preserve;
    boolean force;
  }

  protected final AllProjectsName allProjectsName;
  private final DatabaseDeleteHandler dbHandler;
  private final FilesystemDeleteHandler fsHandler;
  private final CacheDeleteHandler cacheHandler;
  private final ProjectConfigDeleteHandler pcHandler;
  private final Provider<CurrentUser> userProvider;
  private final String pluginName;
  private final DeleteLog deleteLog;
  private final PluginConfigFactory cfgFactory;
  private final HideProject hideProject;
  private PermissionBackend permissionBackend;
  private NotesMigration migration;

  @Inject
  StartReplication(
      AllProjectsNameProvider allProjectsNameProvider,
      DatabaseDeleteHandler dbHandler,
      FilesystemDeleteHandler fsHandler,
      CacheDeleteHandler cacheHandler,
      ProjectConfigDeleteHandler pcHandler,
      Provider<CurrentUser> userProvider,
      @PluginName String pluginName,
      DeleteLog deleteLog,
      PluginConfigFactory cfgFactory,
      HideProject hideProject,
      PermissionBackend permissionBackend,
      NotesMigration migration) {
    this.allProjectsName = allProjectsNameProvider.get();
    this.dbHandler = dbHandler;
    this.fsHandler = fsHandler;
    this.cacheHandler = cacheHandler;
    this.pcHandler = pcHandler;
    this.userProvider = userProvider;
    this.pluginName = pluginName;
    this.deleteLog = deleteLog;
    this.cfgFactory = cfgFactory;
    this.hideProject = hideProject;
    this.permissionBackend = permissionBackend;
    this.migration = migration;
  }

  @Override
  public Object apply(ProjectResource rsrc, Input input)
      throws OrmException, IOException, RestApiException {
    assertDeletePermission(rsrc);
    assertCanDelete(rsrc, input);

    if (input == null || !input.force) {
      Collection<String> warnings = getWarnings(rsrc);
      if (!warnings.isEmpty()) {
        throw new ResourceConflictException(
            String.format("Project %s has open changes", rsrc.getName()));
      }
    }

    doDelete(rsrc, input);
    return Response.none();
  }

  public void assertDeletePermission(ProjectResource rsrc) throws AuthException {
    if (!canDelete(rsrc)) {
      throw new AuthException("not allowed to delete project");
    }
  }

  protected boolean canDelete(ProjectResource rsrc) {
    PermissionBackend.WithUser userPermission = permissionBackend.user(userProvider);
    PermissionBackend.ForProject projectPermission = userPermission.project(rsrc.getNameKey());
    return userPermission.testOrFalse(GlobalPermission.ADMINISTRATE_SERVER)
        || userPermission.testOrFalse(new PluginPermission(pluginName, DELETE_PROJECT))
        || (userPermission.testOrFalse(new PluginPermission(pluginName, DELETE_OWN_PROJECT))
            && projectPermission.testOrFalse(ProjectPermission.WRITE_CONFIG));
  }

  public void assertCanDelete(ProjectResource rsrc, Input input) throws ResourceConflictException {
    try {
      pcHandler.assertCanDelete(rsrc);
      dbHandler.assertCanDelete(rsrc.getProjectState().getProject());
      fsHandler.assertCanDelete(rsrc, input == null ? false : input.preserve);
    } catch (CannotDeleteProjectException e) {
      throw new ResourceConflictException(e.getMessage());
    }
  }

  public Collection<String> getWarnings(ProjectResource rsrc) throws OrmException {
    return dbHandler.getWarnings(rsrc.getProjectState().getProject());
  }

  public void doStartReplication(ProjectResource rsrc, Input input)
      throws OrmException, IOException, RestApiException {
    Project project = rsrc.getProjectState().getProject();
    boolean preserve = input != null && input.preserve;
    Exception ex = null;
    try {
      if (!preserve
          || !cfgFactory
              .getFromGerritConfig(pluginName)
              .getBoolean("hideProjectOnPreserve", false)) {
        if (!migration.disableChangeReviewDb()) {
          dbHandler.delete(project);
        }
        try {
          fsHandler.delete(project, preserve);
        } catch (RepositoryNotFoundException e) {
          throw new ResourceNotFoundException();
        }
        cacheHandler.delete(project);
      } else {
        hideProject.apply(rsrc);
      }
    } catch (Exception e) {
      ex = e;
      throw e;
    } finally {
      deleteLog.onDelete((IdentifiedUser) userProvider.get(), project.getNameKey(), input, ex);
    }
  }
}