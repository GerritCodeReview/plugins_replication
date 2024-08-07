// Copyright (C) 2024 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.api;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.AbstractModule;

public class ApiModule extends AbstractModule {
  @Override
  protected void configure() {
    DynamicItem.itemOf(binder(), ReplicationPushFilter.class);
    DynamicItem.itemOf(binder(), ReplicationConfigOverrides.class);
    DynamicItem.itemOf(binder(), ReplicationRemotesApi.class);
  }
}
