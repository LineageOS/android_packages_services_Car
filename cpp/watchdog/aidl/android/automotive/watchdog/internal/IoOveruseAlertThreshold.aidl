/*
 * Copyright (C) 2020 The Android Open Source Project
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

/**
 * Structure that describes the system-wide disk I/O overuse threshold that triggers I/O overuse
 * alert.
 */
parcelable IoOveruseAlertThreshold {
  /**
   * Duration to aggregate the system-wide disk I/O usage and compare the usage against the given
   * written bytes threshold.
   */
  long aggregateDurationInSecs;

  /**
   * Duration to wait before triggering the disk I/O overuse alert. Disk I/O overuse should be
   * detected for the trigger duration before triggering the alert.
   */
  long triggerDurationInSecs;

  /**
   * Defines the system-wide disk I/O overuse threshold in terms of number of bytes written to
   * all disks on the device by all packages.
   */
  long writtenBytes;
}
