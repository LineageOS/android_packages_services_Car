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

#include "MockPressureChangeCallback.h"
#include "PressureMonitor.h"

#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <gmock/gmock.h>
#include <log/log.h>

#include <sys/epoll.h>

#include <condition_variable>  // NOLINT(build/c++11)
#include <mutex>               // NOLINT(build/c++11)
#include <queue>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::android::sp;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFile;
using ::testing::_;
using ::testing::AllOf;
using ::testing::AnyNumber;
using ::testing::AtLeast;
using ::testing::Eq;
using ::testing::Field;
using ::testing::InSequence;
using ::testing::Matcher;
using ::testing::MockFunction;
using ::testing::NotNull;
using ::testing::Return;
using ::testing::Test;
using ::testing::UnorderedElementsAreArray;

constexpr const char kSamplePsiData[] = "some avg10=0.00 avg60=0.00 avg300=0.00 total=51013728\n"
                                        "full avg10=0.00 avg60=0.00 avg300=0.00 total=25154435";
constexpr std::chrono::milliseconds kTestPollingIntervalMillis = 100ms;
constexpr std::chrono::milliseconds kMaxWaitForResponsesConsumed = 5s;

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
    enum EpollResponse {
        EVENT_TRIGGERED = 0,
        TIMEOUT,
        EPOLL_ERROR,
        EPOLL_HUP,
    };

    struct EpollResponseInfo {
        EpollResponse response = EVENT_TRIGGERED;
        PressureMonitorInterface::PressureLevel highestPressureLevel =
                PressureMonitor::PRESSURE_LEVEL_NONE;
    };

    void SetUp() override {
        mTempProcPressureDir = std::make_unique<TemporaryDir>();
        createPressureFiles();
        mInitPsiMonitorMockFunc = std::make_unique<
                MockFunction<int(enum psi_stall_type, int, int, enum psi_resource)>>();
        mRegisterPsiMonitorMockFunc = std::make_unique<MockFunction<int(int, int, void*)>>();
        mUnregisterPsiMonitorMockFunc = std::make_unique<MockFunction<int(int, int)>>();
        mDestroyPsiMonitorMockFunc = std::make_unique<MockFunction<void(int)>>();
        mEpollWaitMockFunc = std::make_unique<MockFunction<int(int, epoll_event*, int, int)>>();
        mMockPressureChangeCallback = sp<MockPressureChangeCallback>::make();
        mPressureMonitor =
                sp<PressureMonitor>::make(mTempProcPressureDir->path, kTestPollingIntervalMillis,
                                          mInitPsiMonitorMockFunc->AsStdFunction(),
                                          mRegisterPsiMonitorMockFunc->AsStdFunction(),
                                          mUnregisterPsiMonitorMockFunc->AsStdFunction(),
                                          mDestroyPsiMonitorMockFunc->AsStdFunction(),
                                          mEpollWaitMockFunc->AsStdFunction());
        ASSERT_TRUE(
                mPressureMonitor->registerPressureChangeCallback(mMockPressureChangeCallback).ok())
                << "Failed to register pressure change callback";
        MockPsiApis();
    }

    void TearDown() override {
        mPressureMonitor->unregisterPressureChangeCallback(mMockPressureChangeCallback);
        mPressureMonitor->terminate();
        mTempProcPressureDir.reset();
        mInitPsiMonitorMockFunc.reset();
        mRegisterPsiMonitorMockFunc.reset();
        mUnregisterPsiMonitorMockFunc.reset();
        mDestroyPsiMonitorMockFunc.reset();
        mMockPressureChangeCallback.clear();
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
        ON_CALL(*mInitPsiMonitorMockFunc, Call(_, _, _, PSI_MEMORY))
                .WillByDefault([this](enum psi_stall_type stallType, int thresholdUs, int windowUs,
                                      [[maybe_unused]] enum psi_resource _) -> int {
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

        ON_CALL(*mEpollWaitMockFunc, Call(_, _, _, _))
                .WillByDefault([this](int epollFd, epoll_event* events, int maxEvents,
                                      int timeout) -> int {
                    if (mEpollFds.find(epollFd) == mEpollFds.end()) {
                        ALOGE("Failing epoll_wait: Invalid epoll fd received");
                        return -1;
                    }
                    if (events == nullptr ||
                        maxEvents != static_cast<int>(mCachedPsiMonitorInfos.size())) {
                        ALOGE("Failing epoll_wait: Null events or incorrect maxEvents received");
                        return -1;
                    }
                    if (mEpollResponses.empty()) {
                        return 0;
                    }
                    EpollResponseInfo responseInfo = mEpollResponses.front();
                    mEpollResponses.pop();

                    if (responseInfo.response == EPOLL_ERROR ||
                        responseInfo.response == EPOLL_HUP) {
                        events[0].events =
                                responseInfo.response == EPOLL_ERROR ? EPOLLERR : EPOLLHUP;
                        std::unique_lock lock(mMutex);
                        mPollCondition.notify_all();
                        return 1;
                    }

                    if (responseInfo.response == TIMEOUT) {
                        if (timeout == -1) {
                            ALOGE("Failing epoll_wait: Cannot timeout on indefinite wait");
                            std::unique_lock lock(mMutex);
                            mPollCondition.notify_all();
                            return -1;
                        }
                        std::this_thread::sleep_for(std::chrono::milliseconds(timeout));
                    }
                    int totalEvents = 0;
                    for (const auto& info : mCachedPsiMonitorInfos) {
                        if (info.epollData.u32 <= responseInfo.highestPressureLevel) {
                            events[totalEvents].events = 0;
                            events[totalEvents++].data.u32 = info.epollData.u32;
                        }
                    }
                    std::unique_lock lock(mMutex);
                    mPollCondition.notify_all();
                    return totalEvents;
                });
    }

    void queueResponses(const std::vector<EpollResponseInfo>& responses) {
        std::unique_lock lock(mMutex);
        for (const auto& response : responses) {
            mEpollResponses.push(response);
        }
    }

    void waitUntilResponsesConsumed() {
        std::unique_lock lock(mMutex);
        mPollCondition.wait_for(lock, kMaxWaitForResponsesConsumed,
                                [this]() { return mEpollResponses.empty(); });
        // Wait for additional polling interval duration before returning to ensure that any
        // notification message posted at the end of the lopper queue is processed before the test
        // ends.
        std::this_thread::sleep_for(std::chrono::milliseconds(kTestPollingIntervalMillis));
    }

protected:
    std::unique_ptr<TemporaryDir> mTempProcPressureDir;
    std::unique_ptr<MockFunction<int(enum psi_stall_type, int, int, enum psi_resource)>>
            mInitPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<int(int, int, void*)>> mRegisterPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<int(int, int)>> mUnregisterPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<void(int)>> mDestroyPsiMonitorMockFunc;
    std::unique_ptr<MockFunction<int(int, epoll_event*, int, int)>> mEpollWaitMockFunc;
    sp<MockPressureChangeCallback> mMockPressureChangeCallback;

    sp<PressureMonitor> mPressureMonitor;
    std::unordered_set<int> mEpollFds;

    std::vector<PsiMonitorInfo> mCachedPsiMonitorInfos;

private:
    mutable std::mutex mMutex;
    std::condition_variable mPollCondition GUARDED_BY(mMutex);
    std::queue<EpollResponseInfo> mEpollResponses GUARDED_BY(mMutex);
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
            Call(kHighPsiStallLevel, kHighThresholdUs.count(), kPsiWindowSizeUs.count(),
                 PSI_MEMORY))
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

    ASSERT_FALSE(mPressureMonitor->isMonitorActive())
            << "Pressure monitor should be inactive when the initialization has failed";
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

    ASSERT_FALSE(mPressureMonitor->isMonitorActive())
            << "Pressure monitor should be inactive when the initialization has failed";
}

TEST_F(PressureMonitorTest, TestFailToStartMonitorTwice) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    result = mPressureMonitor->start();
    ASSERT_TRUE(result.ok()) << "Failed to start pressure monitor thread. Result: "
                             << result.error();

    ASSERT_TRUE(mPressureMonitor->isMonitorActive());

    result = mPressureMonitor->start();
    ASSERT_FALSE(result.ok()) << "Shouldn't start pressure monitor more than once. Result: "
                              << result.error();
}

TEST_F(PressureMonitorTest, TestPressureEvents) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    {
        InSequence sequence;
        EXPECT_CALL(*mMockPressureChangeCallback,
                    onPressureChanged(PressureMonitor::PRESSURE_LEVEL_MEDIUM))
                .Times(1);
        EXPECT_CALL(*mMockPressureChangeCallback,
                    onPressureChanged(PressureMonitor::PRESSURE_LEVEL_LOW))
                .Times(1);
        EXPECT_CALL(*mMockPressureChangeCallback,
                    onPressureChanged(PressureMonitor::PRESSURE_LEVEL_HIGH))
                .Times(1);
    }
    EXPECT_CALL(*mMockPressureChangeCallback,
                onPressureChanged(PressureMonitor::PRESSURE_LEVEL_NONE))
            .Times(AnyNumber());

    queueResponses(
            {EpollResponseInfo{.response = EVENT_TRIGGERED,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_MEDIUM},
             EpollResponseInfo{.response = TIMEOUT,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_LOW},
             EpollResponseInfo{.response = EVENT_TRIGGERED,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_HIGH}});

    result = mPressureMonitor->start();
    ASSERT_TRUE(result.ok()) << "Failed to start pressure monitor thread. Result: "
                             << result.error();

    waitUntilResponsesConsumed();

    ASSERT_TRUE(mPressureMonitor->isMonitorActive());
}

TEST_F(PressureMonitorTest, TestHighPressureEvents) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    EXPECT_CALL(*mMockPressureChangeCallback,
                onPressureChanged(PressureMonitor::PRESSURE_LEVEL_HIGH))
            .Times(2);

    EXPECT_CALL(*mMockPressureChangeCallback,
                onPressureChanged(PressureMonitor::PRESSURE_LEVEL_NONE))
            .Times(AtLeast(1));

    queueResponses(
            {EpollResponseInfo{.response = EVENT_TRIGGERED,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_HIGH},

             EpollResponseInfo{.response = TIMEOUT,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_NONE},

             EpollResponseInfo{.response = EVENT_TRIGGERED,
                               .highestPressureLevel = PressureMonitor::PRESSURE_LEVEL_HIGH}});

    result = mPressureMonitor->start();
    ASSERT_TRUE(result.ok()) << "Failed to start pressure monitor thread. Result: "
                             << result.error();

    waitUntilResponsesConsumed();

    ASSERT_TRUE(mPressureMonitor->isMonitorActive());
}

TEST_F(PressureMonitorTest, TestFailEpollError) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    EXPECT_CALL(*mMockPressureChangeCallback, onPressureChanged(_)).Times(0);

    queueResponses({EpollResponseInfo{.response = EPOLL_ERROR}});

    result = mPressureMonitor->start();
    ASSERT_TRUE(result.ok()) << "Failed to start pressure monitor thread. Result: "
                             << result.error();

    waitUntilResponsesConsumed();

    ASSERT_FALSE(mPressureMonitor->isMonitorActive()) << "Monitor should stop on epoll error";
}

TEST_F(PressureMonitorTest, TestFailEpollHup) {
    auto result = mPressureMonitor->init();
    ASSERT_TRUE(result.ok()) << "Initialize pressure monitor. Result: " << result.error();

    EXPECT_CALL(*mMockPressureChangeCallback, onPressureChanged(_)).Times(0);

    queueResponses({EpollResponseInfo{.response = EPOLL_HUP}});

    result = mPressureMonitor->start();
    ASSERT_TRUE(result.ok()) << "Failed to start pressure monitor thread. Result: "
                             << result.error();

    waitUntilResponsesConsumed();

    ASSERT_FALSE(mPressureMonitor->isMonitorActive()) << "Monitor should stop on epoll hang up";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
