// Copyright (C) 2017 The Android Open Source Project
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
import java.util.Locale;

/** Some NFS utilities */
public class Nfs {
  /**
   * Determine if a throwable or a cause in its causal chain is a Stale NFS File Handle
   *
   * @param throwable
   * @return a boolean true if the throwable or a cause in its causal chain is a Stale NFS File
   *     Handle
   */
  public static boolean isStaleFileHandleInCausalChain(Throwable throwable) {
    while (throwable != null) {
      if (throwable instanceof IOException && isStaleFileHandle((IOException) throwable)) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }

  /**
   * Determine if an IOException is a Stale NFS File Handle
   *
   * @param ioe
   * @return a boolean true if the IOException is a Stale NFS FIle Handle
   */
  public static boolean isStaleFileHandle(IOException ioe) {
    String msg = ioe.getMessage();
    return msg != null && msg.toLowerCase(Locale.ROOT).matches(".*stale .*file .*handle.*");
  }

  public static <T extends Throwable> void throwIfNotStaleFileHandle(T e) throws T {
    if (!isStaleFileHandleInCausalChain(e)) {
      throw e;
    }
  }
}
