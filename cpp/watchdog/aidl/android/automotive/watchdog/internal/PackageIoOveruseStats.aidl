/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.automotive.watchdog.internal;

import android.automotive.watchdog.internal.PackageIdentifier;
import android.automotive.watchdog.internal.PerStateBytes;

/**
 * Structure that describes the I/O usage stats for a package.
 */
parcelable PackageIoOveruseStats {
  /**
   * Identifier for the package whose stats are stored in the below fields.
   */
  PackageIdentifier packageIdentifier;

  /**
   * Package may be killed when the {@code remainingWriteBytes} are exhausted.
   */
  boolean maybeKilledOnOveruse;

  /**
   * Number of write bytes remaining before overusing disk I/O.
   */
  PerStateBytes remainingWriteBytes;

  /**
   * Period in days for the below stats.
   */
  int periodInDays;

  /**
   * Total number of bytes written to disk during the period.
   */
  PerStateBytes writtenBytes;

  /**
   * Number of overuses during the period.
   */
  int numOveruses;
}
