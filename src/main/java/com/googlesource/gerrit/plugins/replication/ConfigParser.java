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

import java.util.List;
import org.eclipse.jgit.lib.Config;

/** Parser for parsing {@link Config} to a collection of {@link RemoteConfiguration} objects */
public interface ConfigParser {

  /**
   * parse the new replication config
   *
   * @param config new configuration to parse
   * @return List of parsed {@link RemoteConfiguration}
   * @throws ReplicationConfigurationException if the new configuration is not valid.
   */
  List<RemoteConfiguration> parseRemotes(Config config) throws ReplicationConfigurationException;

  /**
   * Exception to be thrown when configuration options are not valid or contradicting from the
   * replication domain perspective.
   */
  static class ReplicationConfigurationException extends Exception {
    private static final long serialVersionUID = 1L;

    public ReplicationConfigurationException(String msg) {
      super(msg);
    }

    public ReplicationConfigurationException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }
}
