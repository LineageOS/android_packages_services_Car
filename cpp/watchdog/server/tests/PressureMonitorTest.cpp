/*
 * Copyright 2024 The Android Open Source Project
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

#include "PressureMonitor.h"

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <gmock/gmock.h>
#include <log/log.h>

#include <sys/epoll.h>

#include <queue>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFile;
using ::testing::_;
using ::testing::AllOf;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Matcher;
using ::testing::MockFunction;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::Test;
using ::testing::UnorderedElementsAreArray;

namespace {
constexpr const char kSamplePsiData[] = "some avg10=0.00 avg60=0.00 avg300=0.00 total=51013728\n"
                                        "full avg10=0.00 avg60=0.00 avg300=0.00 total=25154435";

enum PsiMonitorState {
    INITIALIZED = 0,
    REGISTERED,
    UNREGISTERED,
    DESTROYED,
    STATE_COUNT,
};

struct PsiMonitorInfo {
    const psi_stall_type kStallType;
    const int kThresholdUs;
    const int kWindowUs;
    epoll_data_t epollData;
    PsiMonitorState state;

    std::string toString() const {
        std::string buffer;
        StringAppendF(&buffer,
                      "PsiMonitorInfo{kStallType = %d, kThresholdUs = %d, kWindowUs = %d, "
                      "epollData = %d, state = %d}",
                      kStallType, kThresholdUs, kWindowUs, epollData.u32, state);
        return buffer;
    }
};

std::string toString(const std::vector<PsiMonitorInfo>& psiMonitorInfos) {
    std::string buffer;
    for (const PsiMonitorInfo& info : psiMonitorInfos) {
        StringAppendF(&buffer, "%s\n", info.toString().c_str());
    }
    return buffer;
}

MATCHER_P(EpollDataEq, expected, "") {
    return ExplainMatchResult(Field("u32", &epoll_data::u32, Eq(expected.u32)), arg,
                              result_listener);
}

MATCHER_P(PsiMonitorInfoEq, expected, "") {
    return ExplainMatchResult(AllOf(Field("kStallType", &PsiMonitorInfo::kStallType,
                                          Eq(expected.kStallType)),
                                    Field("kThresholdUs", &PsiMonitorInfo::kThresholdUs,
                                          Eq(expected.kThresholdUs)),
                                    Field("kWindowUs", &PsiMonitorInfo::kWindowUs,
                                          Eq(expected.kWindowUs)),
                                    Field("epollData.u32", &PsiMonitorInfo::epollData,
                                          EpollDataEq(expected.epollData)),
                                    Field("state", &PsiMonitorInfo::state, Eq(expected.state))),
                              arg, result_listener);
}

// TODO(b/335508921): Once stats are read from system properties, mock the system property APIs to
//   pass this value.
const std::vector<PsiMonitorInfo> kDefaultPsiMonitorInfos =
        {PsiMonitorInfo{
                 .kStallType = kLowPsiStallLevel,
                 .kThresholdUs = kLowThresholdUs.count(),
                 .kWindowUs = kPsiWindowSizeUs.count(),
                 .epollData.u32 = PressureMonitor::PRESSURE_LEVEL_LOW,
                 .state = REGISTERED,
         },
         PsiMonitorInfo{
                 .kStallType = kMediumPsiStallLevel,
                 .kThresholdUs = kMediumThresholdUs.count(),
                 .kWindowUs = kPsiWindowSizeUs.count(),
                 .epollData.u32 = PressureMonitor::PRESSURE_LEVEL_MEDIUM,
                 .state = REGISTERED,
         },
         PsiMonitorInfo{
                 .kStallType = kHighPsiStallLevel,
                 .kThresholdUs = kHighThresholdUs.count(),
                 .kWindowUs = kPsiWindowSizeUs.count(),
                 .epollData.u32 = PressureMonitor::PRESSURE_LEVEL_HIGH,
                 .state = REGISTERED,
         }};

std::vector<Matcher<const PsiMonitorInfo&>> getPsiMonitorInfoMatchers(
        const std::vector<PsiMonitorInfo>& psiMonitorInfos) {
    std::vector<Matcher<const PsiMonitorInfo&>> psiMonitorInfoMatchers;
    for (const auto& psiMonitorInfo : psiMonitorInfos) {
        psiMonitorInfoMatchers.push_back(PsiMonitorInfoEq(psiMonitorInfo));
    }
    return psiMonitorInfoMatchers;
}

}  // namespace

class PressureMonitorTest : public Test {
protected:
    void SetUp() override {
        mTempProcPressureDir = std::make_unique<TemporaryDir>();
        createPressureFiles();
        mInitPsiMonitorMockFunc =
                std::make_unique<MockFunction<int(enum psi_stall_type, int, int)>>();
        mRegisterPsiMonitorMockFunc = std::make_unique<MockFunction<int(int, int, void*)>>();
        mUnregisterPsiMonitorMockFunc = std::make_unique<MockFunction<int(int, int)>>();
        mDestroyPsiMonitorMockFunc = std::make_unique<MockFunction<void(int)>>();
        mPressureMonitor = sp<PressureMonitor>::make(mTempProcPressureDir->path,
                                                     mInitPsiMonitorMockFunc->AsStdFunction(),
                                                     mRegisterPsiMonitorMockFunc->AsStdFunction(),
                                                     mUnregisterPsiMonitorMockFunc->AsStdFunction(),
                                                     mDestroyPsiMonitorMockFunc->AsStdFunction());
        MockPsiApis();
    }

    void TearDown() override {
        mPressureMonitor->terminate();
        mTempProcPressureDir.reset();
        mInitPsiMonitorMockFunc.reset();
        mRegisterPsiMonitorMockFunc.reset();
        mUnregisterPsiMonitorMockFunc.reset();
        mDestroyPsiMonitorMockFunc.reset();
        mPressureMonitor.clear();
        mCachedPsiMonitorInfos.clear();
    }

    void createPressureFiles() {
        std::string path = StringPrintf("%s/%s", mTempProcPressureDir->path, kMemoryFile);
        ASSERT_TRUE(WriteStringToFile(kSamplePsiData, path))
                << "Failed to write memory psi data to file '" << path << "'";
    }

    void MockPsiApis() {
        // Note: For failure case, mock individual calls and return error.
        ON_CALL(*mInitPsiMonitorMockFunc, Call(_, _, _))
                .WillByDefault([this](enum psi_stall_type stallType, int thresholdUs,
                                      int windowUs) -> int {
                    mCachedPsiMonitorInfos.push_back(PsiMonitorInfo{
                            .kStallType = stallType,
                            .kThresholdUs = thresholdUs,
                            .kWindowUs = windowUs,
                            .state = INITIALIZED,
                    });
                    // Return the index in mCachedPsiMonitorInfos as the FD.
                    return mCachedPsiMonitorInfos.size() - 1;
                });

        ON_CALL(*mRegisterPsiMonitorMockFunc, Call(_, _, NotNull()))
                .WillByDefault([this](int epollFd, int fd, void* pressureLevel) -> int {
                    // mInitPsiMonitorMockFunc returns an index in mCachedPsiMonitorInfos as the
                    // FD.
                    if (fd < 0 || fd >= static_cast<int>(mCachedPsiMonitorInfos.size())) {
                        ALOGE("Failing register_psi_monitor call: FD is out of bounds");
                        return -1;
                    }
                    mCachedPsiMonitorInfos[fd].epollData.ptr = pressureLevel;
                    mCachedPsiMonitorInfos[fd].state = REGISTERED;
                    mEpollFds.insert(epollFd);
                    return 0;
                });

        ON_CALL(*mUnregisterPsiMonitorMockFunc, Call(_, _))
                .WillByDefault([this](int epollFd, int fd) -> int {
                    if (mEpollFds.empty() || mCachedPsiMonitorInfos.empty()) {
                        ALOGE("Failing unregister_psi_monitor call: No monitors are registered");
                        return -1;
                    }
                    // mInitPsiMonitorMockFunc returns an index in mCachedPsiMonitorInfos as the
                    // FD.
                    if (fd < 0 || fd >= static_cast<int>(mCachedPsiMonitorInfos.size())) {
                        ALOGE("Failing unregister_psi_monitor call: FD is out of bounds");
                        return -1;
                    }
                    // mEpollFds should contain only one unique FD.
                    if (mEpollFds.find(epollFd) == mEpollFds.end()) {
                        ALOGE("Failing unregister_psi_monitor call: Received epoll FD %d doesn't "
                              "match %d",
                              epollFd, *mEpollFds.begin());
                        return -1;
                    }
                    if (mCachedPsiMonitorInfos[fd].state != REGISTERED) {
                        ALOGE("Failing unregister_psi_monitor call: FD is not in registered state");
                        return -1;
                    }
                    mCachedPsiMonitorInfos[fd].epollData.ptr = nullptr;
                    mCachedPsiMonitorInfos[fd].state = UNREGISTERED;
                    return 0;
                });

        ON_CALL(*mDestroyPsiMonitorMockFunc, Call(_)).WillByDefault([this](int fd) -> void {
            // mInitPsiMonitorMockFunc returns an index in mCachedPsiMonitorInfos as the FD.
            if (fd < 0 || fd >= static_cast<int>(mCachedPsiMonitorInfos.size())) {
                ALOGE("Failing destroy_psi_monitor call: FD is out of bounds");
                return;
            }
            if (mCachedPsiMonitorInfos[fd].epollData.ptr != nullptr) {
                ALOGE("Failing destroy_psi_monitor call: epoll data is not null");
                return;
            }
            mCachedPsiMonitorInfos[fd].state = DESTROYED;
            // Do not erase the entry from mCachedPsiMonitorInfos. Otherwise, indexing based on fd
            // won't work for following entries.
        });
    }

protected:
    std::unique_ptr<TemporaryDir> mTempProcPressureDir;
    std::unique_ptr<MockFunction<int(enum psi_stall_type, int, int)>> mInitPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<int(int, int, void*)>> mRegisterPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<int(int, int)>> mUnregisterPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<void(int)>> mDestroyPsiMonitorMockFunc;

    sp<PressureMonitor> mPressureMonitor;
    std::unordered_set<int> mEpollFds;

    std::vector<PsiMonitorInfo> mCachedPsiMonitorInfos;
};

TEST_F(PressureMonitorTest, TestInitializeAndTerminate) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    std::vector<PsiMonitorInfo> expectedPsiMonitorInfos = kDefaultPsiMonitorInfos;
    EXPECT_THAT(mCachedPsiMonitorInfos,
                UnorderedElementsAreArray(getPsiMonitorInfoMatchers(expectedPsiMonitorInfos)))
            << "PSI monitors after initialization.\nExpected:\n"
            << toString(expectedPsiMonitorInfos) << "Actual:\n"
            << toString(mCachedPsiMonitorInfos);

    mPressureMonitor->terminate();

    for (auto& info : expectedPsiMonitorInfos) {
        info.epollData.ptr = nullptr;
        info.state = DESTROYED;
    }

    EXPECT_THAT(mCachedPsiMonitorInfos,
                UnorderedElementsAreArray(getPsiMonitorInfoMatchers(expectedPsiMonitorInfos)))
            << "PSI monitors after termination.\nExpected:\n"
            << toString(expectedPsiMonitorInfos) << "Actual:\n"
            << toString(mCachedPsiMonitorInfos);
}

TEST_F(PressureMonitorTest, TestFailInitPsiMonitor) {
    ON_CALL(*mInitPsiMonitorMockFunc,
            Call(kHighPsiStallLevel, kHighThresholdUs.count(), kPsiWindowSizeUs.count()))
            .WillByDefault(Return(-1));

    auto result = mPressureMonitor->init();
    ASSERT_FALSE(result.ok()) << "Initialization should fail on error";

    std::vector<PsiMonitorInfo> expectedPsiMonitorInfos{kDefaultPsiMonitorInfos[0],
                                                        kDefaultPsiMonitorInfos[1]};

    for (auto& info : expectedPsiMonitorInfos) {
        info.epollData.ptr = nullptr;
        info.state = DESTROYED;
    }

    EXPECT_THAT(mCachedPsiMonitorInfos,
                UnorderedElementsAreArray(getPsiMonitorInfoMatchers(expectedPsiMonitorInfos)))
            << "PSI monitors after initialization failure.\nExpected:\n"
            << toString(expectedPsiMonitorInfos) << "Actual:\n"
            << toString(mCachedPsiMonitorInfos);

    ASSERT_FALSE(mPressureMonitor->start().ok())
            << "Should fail to start pressure monitor when the initialization has failed";
}

TEST_F(PressureMonitorTest, TestFailRegisterPsiMonitor) {
    ON_CALL(*mRegisterPsiMonitorMockFunc,
            Call(_, _, reinterpret_cast<void*>(PressureMonitor::PRESSURE_LEVEL_HIGH)))
            .WillByDefault(Return(-1));

    auto result = mPressureMonitor->init();
    ASSERT_FALSE(result.ok()) << "Initialization should fail on error";

    std::vector<PsiMonitorInfo> expectedPsiMonitorInfos = kDefaultPsiMonitorInfos;
    for (auto& info : expectedPsiMonitorInfos) {
        info.epollData.ptr = nullptr;
        info.state = DESTROYED;
    }

    EXPECT_THAT(mCachedPsiMonitorInfos,
                UnorderedElementsAreArray(getPsiMonitorInfoMatchers(expectedPsiMonitorInfos)))
            << "PSI monitors after registration failure.\nExpected:\n"
            << toString(expectedPsiMonitorInfos) << "Actual:\n"
            << toString(mCachedPsiMonitorInfos);

    ASSERT_FALSE(mPressureMonitor->start().ok())
            << "Should fail to start pressure monitor when the initialization has failed";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
