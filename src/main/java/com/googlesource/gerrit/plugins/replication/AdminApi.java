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

import com.google.gerrit.entities.Project;

public interface AdminApi {

  /**
   * Create a new project without honouring the reflog configuration.
   *
   * @param project the new project to create
   * @param head initial value for the HEAD
   * @return true if the project was created
   * @deprecated this method should not be used as it does not respect the reflog settings
   *     <p>Use {@link AdminApi#createProject(Project.NameKey,String,boolean)} instead.
   */
  @Deprecated(since = "3.14", forRemoval = true)
  boolean createProject(Project.NameKey project, String head);

  /**
   * Create a new project honouring the reflog configuration.
   *
   * @param project the new project to create
   * @param head initial value for the HEAD
   * @param enableRefLog true if the reflog tracking should be enabled
   * @return true if the project was created
   */
  default boolean createProject(Project.NameKey project, String head, boolean enableRefLog) {
    return createProject(project, head);
  }

  boolean deleteProject(Project.NameKey project);

  boolean updateHead(Project.NameKey project, String newHead);
}
