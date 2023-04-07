// Copyright (C) 2019 The Android Open Source Project
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

import com.google.common.io.Files;
import com.google.gerrit.common.Nullable;

public class Template {

  static final String SUBTITUTION_PROJECT_NAME = "${name}";

  @Nullable
  public static String substitute(
      RemoteConfiguration config, String in, String name, boolean keyIsMandatory) {
    String remoteNameStyle = config.getRemoteNameStyle();
    if (remoteNameStyle.equals("dash")) {
      name = name.replace("/", "-");
    } else if (remoteNameStyle.equals("underscore")) {
      name = name.replace("/", "_");
    } else if (remoteNameStyle.equals("basenameOnly")) {
      name = Files.getNameWithoutExtension(name);
    } else if (!remoteNameStyle.equals("slash")) {
      repLog.atFine().log("Unknown remoteNameStyle: %s, falling back to slash", remoteNameStyle);
    }

    int n = in.indexOf(SUBTITUTION_PROJECT_NAME);
    if (0 <= n) {
      return in.substring(0, n) + name + in.substring(n + SUBTITUTION_PROJECT_NAME.length());
    }
    if (!keyIsMandatory) {
      return in;
    }
    return null;
  }

  public static boolean isTemplate(String in) {
    return in == null ? false : in.indexOf(SUBTITUTION_PROJECT_NAME) != -1;
  }
}
