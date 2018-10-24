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
import com.google.gerrit.reviewdb.client.Project;
import java.util.Collections;
import java.util.List;

public class ReplicationFilter {
  public enum PatternType {
    REGEX,
    WILDCARD,
    EXACT_MATCH;
  }

  public static ReplicationFilter all() {
    return new ReplicationFilter(Collections.<String>emptyList());
  }

  public static PatternType getPatternType(String pattern) {
    if (pattern.startsWith(AccessSection.REGEX_PREFIX)) {
      return PatternType.REGEX;
    } else if (pattern.endsWith("*")) {
      return PatternType.WILDCARD;
    } else {
      return PatternType.EXACT_MATCH;
    }
  }

  private final List<String> projectPatterns;

  public ReplicationFilter(List<String> patterns) {
    projectPatterns = patterns;
  }

  public boolean matches(Project.NameKey name) {
    if (projectPatterns.isEmpty()) {
      return true;
    }
    String projectName = name.get();

    for (String pattern : projectPatterns) {
      if (matchesPattern(projectName, pattern)) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesPattern(String projectName, String pattern) {
    boolean match = false;
    switch (getPatternType(pattern)) {
      case REGEX:
        match = projectName.matches(pattern);
        break;
      case WILDCARD:
        match = projectName.startsWith(pattern.substring(0, pattern.length() - 1));
        break;
      case EXACT_MATCH:
        match = projectName.equals(pattern);
    }
    return match;
  }
}
