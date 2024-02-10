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

import com.google.inject.AbstractModule;
import com.googlesource.gerrit.plugins.replication.api.ApiModule;

/**
 * Gerrit libModule for applying a ref-filter for outgoing replications.
 *
 * <p>It should be used only when an actual filter is defined, otherwise the default replication
 * plugin behaviour will be pushing all refs without any filtering.
 */
public class ReplicationExtensionPointModule extends AbstractModule {

  @Override
  protected void configure() {
    install(new ApiModule());
  }
}
