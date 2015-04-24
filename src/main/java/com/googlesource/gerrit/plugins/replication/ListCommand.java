// Copyright (C) 2015 The Android Open Source Project
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

import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.gson.JsonObject;
import com.google.inject.Inject;

import com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType;

import org.kohsuke.args4j.Option;

import java.util.List;

@CommandMetaData(name = "list", description = "List specific slaves information")
final class ListCommand extends SshCommand {
  private static final char endl = '\n';

  @Option(name = "--detail", usage = "Display slave detail information")
  private boolean detail;

  @Option(name = "--slave", metaVar = "PATTERN", usage = "pattern to match slave name on")
  private String slave;

  @Option(name = "--format", usage = "plain|json output format, plain is the default")
  private String format;

  @Inject
  private ReplicationConfig config;

  @Override
  protected void run() {
    List<Destination> dest = config.getDestinations(FilterType.ALL);
    for (Destination d : dest) {
      if (matches(d.getRemoteConfig().getName())) {
        printSlave(d, detail);
      }
    }
  }

  private boolean matches(final String name) {
    if (slave == null || slave.isEmpty()) {
      return true;
    }

    if (name.contains(slave) || name.matches(slave)) {
      return true;
    }

    return false;
  }

  private void addProperty(JsonObject obj, final String key, String[] values) {
    if (values.length > 0) {
      StringBuilder props = new StringBuilder();
      for (String v : values) {
        props.append(v);
      }
      obj.addProperty(key, props.toString());
    }
  }

  private void printSlave(Destination d, boolean detail) {
    if ("json".equalsIgnoreCase(format)) {
      JsonObject obj = new JsonObject();
      obj.addProperty("slave", d.getRemoteConfig().getName());
      addProperty(obj, "Url", d.getUrls());
      if (detail) {
        addProperty(obj, "AdminUrl", d.getAdminUrls());
        addProperty(obj, "AuthGroup", d.getAuthGroupNames());
        addProperty(obj, "Project", d.getProjects());
      }
      stdout.print(obj.toString() + endl);
    } else {
      StringBuilder out = new StringBuilder();
      out.append("Slave: " + d.getRemoteConfig().getName() + endl);
      for (String url : d.getUrls()) {
        out.append("Url: " + url + endl);
      }

      if (detail) {
        for (String adminUrl : d.getAdminUrls()) {
          out.append("AdminUrl: " + adminUrl + endl);
        }

        for (String authGroup : d.getAuthGroupNames()) {
          out.append("AuthGroup: " + authGroup + endl);
        }

        for (String project : d.getProjects()) {
          out.append("Project: " + project + endl);
        }
      }
      stdout.print(out.toString() + endl);
    }
  }
}
