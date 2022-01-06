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

#include "Enumerator.h"
#include "MockServiceFactory.h"
#include "MockStatsCollector.h"
#include "ServiceFactory.h"

#include <android/hardware/automotive/evs/1.1/IEvsEnumerator.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

using ::android::automotive::evs::V1_1::implementation::Enumerator;
using ::android::automotive::evs::V1_1::implementation::MockServiceFactory;
using ::android::automotive::evs::V1_1::implementation::MockStatsCollector;

TEST(Enumerator, BuildsNullObjectWithoutServiceNameProvided) {
    EXPECT_EQ(Enumerator::build(nullptr), nullptr);
}

TEST(Enumerator, ReturnsNullWhenNullNamePassed) {
    EXPECT_EQ(Enumerator::build(static_cast<const char*>(nullptr)), nullptr);
}

TEST(Enumerator, ReturnsNullWhenServiceNotAvailable) {
    auto mockServiceFactory = std::make_unique<MockServiceFactory>();
    ON_CALL(*mockServiceFactory, getService).WillByDefault(::testing::Invoke([&]() {
        return nullptr;
    }));
    EXPECT_EQ(Enumerator::build(std::move(mockServiceFactory),
                                std::make_unique<MockStatsCollector>()),
              nullptr);
}

TEST(Enumerator, ConstructsAndDestroys) {
    EXPECT_NE(Enumerator::build(std::make_unique<MockServiceFactory>(),
                                std::make_unique<MockStatsCollector>()),
              nullptr);
}
