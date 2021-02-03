/**
 * Copyright (c) 2020, The Android Open Source Project
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

#ifndef CPP_WATCHDOG_SERVER_TESTS_MOCKCARWATCHDOGSERVICEFORSYSTEM_H_
#define CPP_WATCHDOG_SERVER_TESTS_MOCKCARWATCHDOGSERVICEFORSYSTEM_H_

#include <android/automotive/watchdog/internal/ICarWatchdogServiceForSystem.h>
#include <android/automotive/watchdog/internal/TimeoutLength.h>
#include <binder/IBinder.h>
#include <binder/Status.h>
#include <gmock/gmock.h>
#include <utils/RefBase.h>
#include <utils/String16.h>
#include <utils/StrongPointer.h>

namespace android {
namespace automotive {
namespace watchdog {

class MockBinder : public android::BBinder {
public:
    MockBinder() {
        EXPECT_CALL(*this, linkToDeath(::testing::_, nullptr, 0))
                .WillRepeatedly(::testing::Return(OK));
        EXPECT_CALL(*this, unlinkToDeath(::testing::_, nullptr, 0, nullptr))
                .WillRepeatedly(::testing::Return(OK));
    }
    MOCK_METHOD(status_t, linkToDeath,
                (const sp<android::IBinder::DeathRecipient>& recipient, void* cookie,
                 uint32_t flags),
                (override));
    MOCK_METHOD(status_t, unlinkToDeath,
                (const wp<android::IBinder::DeathRecipient>& recipient, void* cookie,
                 uint32_t flags, wp<android::IBinder::DeathRecipient>* outRecipient),
                (override));
};

class MockCarWatchdogServiceForSystem :
      public android::automotive::watchdog::internal::ICarWatchdogServiceForSystemDefault {
public:
    MockCarWatchdogServiceForSystem() : mBinder(new MockBinder()) {
        EXPECT_CALL(*this, onAsBinder()).WillRepeatedly(::testing::Return(mBinder.get()));
    }

    sp<MockBinder> getBinder() const { return mBinder; }

    MOCK_METHOD(android::IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(android::binder::Status, checkIfAlive,
                (int32_t, android::automotive::watchdog::internal::TimeoutLength), (override));
    MOCK_METHOD(android::binder::Status, prepareProcessTermination, (), (override));
    MOCK_METHOD(android::binder::Status, getPackageInfosForUids,
                (const std::vector<int32_t>& uids,
                 const std::vector<::android::String16>& vendorPackagePrefixes,
                 std::vector<android::automotive::watchdog::internal::PackageInfo>* packageInfos),
                (override));

private:
    sp<MockBinder> mBinder;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  //  CPP_WATCHDOG_SERVER_TESTS_MOCKCARWATCHDOGSERVICEFORSYSTEM_H_
