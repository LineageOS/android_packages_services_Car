/*
 * Copyright (c) 2025, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "PackageInfoTestUtils.h"
#include "UidStatsCollectorBase.h"

#include <gmock/gmock.h>

#include <inttypes.h>

#include <string>

namespace android {
namespace automotive {
namespace watchdog {

/** Returns info about the UidBaseStats. */
std::string toString(const UidBaseStats& uidBaseStats);

/** Aggregates UidBaseStats info and returns them. */
std::string toString(const std::vector<UidBaseStats>& uidBaseStats);

std::vector<testing::Matcher<const UidBaseStats&>> UidBaseStatsMatchers(
        const std::vector<UidBaseStats>& uidBaseStats);

/** Returns sample package infos per UID for testing. */
std::unordered_map<uid_t, aidl::android::automotive::watchdog::internal::PackageInfo>
samplePackageInfoByUid();

/** Returns sample UID I/O stats per UID for testing. */
std::unordered_map<uid_t, UidIoStats> sampleUidIoStatsByUid();

/** Returns sample UID base stats for testing. */
std::vector<UidBaseStats> sampleUidBaseStats();

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
