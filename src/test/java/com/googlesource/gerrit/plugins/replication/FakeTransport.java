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

import java.io.IOException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;

public class FakeTransport extends Transport {

  public FakeTransport() throws IOException {
    super(new InMemoryRepository.Builder().build(), null);
  }

  @Override
  public FetchConnection openFetch() throws NotSupportedException, TransportException {
    return null;
  }

  @Override
  public PushConnection openPush() throws NotSupportedException, TransportException {
    return null;
  }

  @Override
  public void close() {}
}
