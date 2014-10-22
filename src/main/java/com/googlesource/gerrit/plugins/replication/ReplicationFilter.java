// Copyright (C) 2014 The Android Open Source Project
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

import com.google.gerrit.common.data.AccessSection;
import com.google.gerrit.reviewdb.client.Project.NameKey;

import java.util.List;

public class ReplicationFilter {
  private List<String> projectsToMatch;
  private boolean matchAll;

  public ReplicationFilter(List<String> names) {
    matchAll = false;
    projectsToMatch = names;
  }

  private ReplicationFilter() {
    matchAll = true;
  }

  public static ReplicationFilter all() {
    return new ReplicationFilter();
  }

  public static boolean isRE(String str) {
    return str.startsWith(AccessSection.REGEX_PREFIX);
  }

  public static boolean isWildcard(String str) {
    return str.endsWith("*");
  }

  public boolean matches(NameKey name) {
    if (matchAll) {
      return true;
    } else {
      String projectName = name.get();
      for (final String projectMatch : projectsToMatch) {
        if (isRE(projectMatch)) {
          // projectMatch is a regular expression
          if (projectName.matches(projectMatch)) {
            return true;
          }
        } else if (isWildcard(projectMatch)) {
          // projectMatch is a wildcard
          if (projectName.startsWith(projectMatch.substring(0,
              projectMatch.length() - 1))) {
            return true;
          }
        } else {
          // No special case, so we try to match directly
          if (projectName.equals(projectMatch)) {
            return true;
          }
        }
      }

      // Nothing matched, so don't push the project
      return false;
    }
  }
}
