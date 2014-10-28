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

import com.google.common.collect.ImmutableList;
import com.google.gerrit.common.data.AccessSection;
import com.google.gerrit.reviewdb.client.Project.NameKey;

import java.util.List;

public class ReplicationFilter {
  private final List<String> projectPatterns;

  public ReplicationFilter(List<String> patterns) {
    projectPatterns = patterns;
  }

  public static ReplicationFilter all() {
    return new ReplicationFilter(ImmutableList.<String>of());
  }

  public static boolean isRE(String str) {
    return str.startsWith(AccessSection.REGEX_PREFIX);
  }

  public static boolean isWildcard(String str) {
    return str.endsWith("*");
  }

  public boolean matches(NameKey name) {
    if (projectPatterns.isEmpty()) {
      return true;
    } else {
      String projectName = name.get();
      for (String pattern : projectPatterns) {
        if (isRE(pattern)) {
          // pattern is a regular expression
          if (projectName.matches(pattern)) {
            return true;
          }
        } else if (isWildcard(pattern)) {
          // pattern is a wildcard pattern
          if (projectName.startsWith(pattern.substring(0,
              pattern.length() - 1))) {
            return true;
          }
        } else {
          // No special case, so we try to match directly
          if (projectName.equals(pattern)) {
            return true;
          }
        }
      }

      // Nothing matched, so don't push the project
      return false;
    }
  }
}
