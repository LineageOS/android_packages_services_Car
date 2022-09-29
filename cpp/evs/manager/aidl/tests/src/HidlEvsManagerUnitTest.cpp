/*
 * Copyright 2022 The Android Open Source Project
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
#include "MockEvsHal.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "utils/include/Utils.h"
#include "wrappers/include/HidlEnumerator.h"

#include <cutils/android_filesystem_config.h>
#include <ui/DisplayMode.h>
#include <ui/DisplayState.h>

#include <unistd.h>

namespace {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::IEvsEnumerator;
using ::aidl::android::hardware::automotive::evs::Stream;
using ::android::hardware::hidl_vec;

namespace hidlevs = ::android::hardware::automotive::evs;

constexpr size_t kNumMockEvsCameras = 4;
constexpr size_t kNumMockEvsDisplays = 2;

const std::unordered_set<int32_t> gAllowedUid({AID_ROOT, AID_SYSTEM, AID_AUTOMOTIVE_EVS});

}  // namespace

namespace aidl::android::automotive::evs::implementation {

class HidlEvsEnumeratorUnitTest : public ::testing::Test {
public:
    HidlEvsEnumeratorUnitTest() {
        // Instantiates IEvsEnumerator
        mAidlEnumerator = ndk::SharedRefBase::make<Enumerator>();
        EXPECT_NE(nullptr, mAidlEnumerator);

        // Disable a permission check
        mAidlEnumerator->enablePermissionCheck(/* enable= */ false);
    }

    ~HidlEvsEnumeratorUnitTest() override {
        // Clean-up work should be here.  No exception should be thrown.
    }

    void SetUp() override {
        // Additional place to set up the test environment.  This will be called
        // right after the constructor.
        mMockEvsHal = std::make_shared<MockEvsHal>(kNumMockEvsCameras, kNumMockEvsDisplays);
        EXPECT_NE(nullptr, mMockEvsHal);
        mMockEvsHal->initialize();

        std::shared_ptr<IEvsEnumerator> hwEnumerator = mMockEvsHal->getEnumerator();
        EXPECT_NE(nullptr, hwEnumerator);
        mAidlEnumerator->init(hwEnumerator, /* enableMonitor= */ true);

        mEnumerator = new (std::nothrow) HidlEnumerator(mAidlEnumerator);
    }

    void TearDown() override {
        // This will be called immediately after each test; right before the
        // destructor.
    }

protected:
    // Class members declared here can be used by all tests in the test suite
    // for EvsEnumerator
    std::shared_ptr<Enumerator> mAidlEnumerator;
    ::android::sp<HidlEnumerator> mEnumerator;

private:
    std::shared_ptr<MockEvsHal> mMockEvsHal;
};

TEST_F(HidlEvsEnumeratorUnitTest, VerifyPermissionCheck) {
    bool isAllowedUid = gAllowedUid.find(getuid()) != gAllowedUid.end();
    mAidlEnumerator->enablePermissionCheck(true);

    hidl_vec<hidlevs::V1_1::CameraDesc> list;
    ::android::hardware::camera::device::V3_2::Stream emptyConfig;
    if (!isAllowedUid) {
        EXPECT_FALSE(
                mEnumerator->getCameraList_1_1([&list](auto received) { list = received; }).isOk());

        ::android::sp<hidlevs::V1_1::IEvsCamera> invalidCamera =
                mEnumerator->openCamera_1_1(/* cameraId = */ "invalidId", emptyConfig);
        EXPECT_EQ(invalidCamera, nullptr);
        EXPECT_TRUE(mEnumerator->closeCamera(invalidCamera).isOk());

        ::android::sp<hidlevs::V1_1::IEvsDisplay> invalidDisplay =
                mEnumerator->openDisplay_1_1(/* displayId= */ 0xFF);
        EXPECT_EQ(invalidDisplay, nullptr);

        hidlevs::V1_0::DisplayState displayState = mEnumerator->getDisplayState();
        EXPECT_EQ(hidlevs::V1_0::DisplayState::DEAD, displayState);
    }

    // TODO(b/240619903): Adds more lines to verify the behavior when
    //                    current user is allowed to use the EVS service.
    mAidlEnumerator->enablePermissionCheck(false);
}

TEST_F(HidlEvsEnumeratorUnitTest, VerifyIsHardwareMethod) {
    EXPECT_FALSE(mEnumerator->isHardware());
}

TEST_F(HidlEvsEnumeratorUnitTest, VerifyOpenAndCloseDisplay) {
    std::vector<uint8_t> displays;

    EXPECT_TRUE(mEnumerator->getDisplayIdList([&displays](const auto& list) { displays = list; })
                        .isOk());
    EXPECT_EQ(kNumMockEvsDisplays, displays.size());

    for (auto& id : displays) {
        ::android::sp<hidlevs::V1_1::IEvsDisplay> h0, h1;
        h0 = mEnumerator->openDisplay_1_1(id);
        EXPECT_NE(nullptr, h0);

        h1 = mEnumerator->openDisplay_1_1(id);
        EXPECT_NE(nullptr, h1);

        ::android::ui::DisplayMode displayMode;
        ::android::ui::DisplayState displayState;
        EXPECT_TRUE(
                h1->getDisplayInfo_1_1([&](const auto& config, const auto& state) {
                      displayMode =
                              *(reinterpret_cast<const ::android::ui::DisplayMode*>(config.data()));
                      displayState =
                              *(reinterpret_cast<const ::android::ui::DisplayState*>(state.data()));
                  }).isOk());

        hidlevs::V1_0::DisplayState state = mEnumerator->getDisplayState();
        EXPECT_EQ(hidlevs::V1_0::DisplayState::NOT_VISIBLE, state);

        EXPECT_TRUE(mEnumerator->closeDisplay(h1).isOk());

        // closeDisplay() with an invalidated display handle should be okay.
        EXPECT_TRUE(mEnumerator->closeDisplay(h0).isOk());
    }
}

TEST_F(HidlEvsEnumeratorUnitTest, VerifyOpenAndCloseCamera) {
    hidl_vec<hidlevs::V1_1::CameraDesc> hidlCameras;
    EXPECT_TRUE(
            mEnumerator
                    ->getCameraList_1_1([&hidlCameras](auto received) { hidlCameras = received; })
                    .isOk());
    EXPECT_EQ(kNumMockEvsCameras, hidlCameras.size());

    std::vector<CameraDesc> aidlCameras;
    EXPECT_TRUE(mAidlEnumerator->getCameraList(&aidlCameras).isOk());
    EXPECT_EQ(kNumMockEvsCameras, aidlCameras.size());

    for (auto i = 0; i < hidlCameras.size(); ++i) {
        CameraDesc& aidlCamera = aidlCameras[i];
        hidlevs::V1_1::CameraDesc hidlCamera = hidlCameras[i];

        std::vector<Stream> configs;
        EXPECT_TRUE(mAidlEnumerator->getStreamList(aidlCamera, &configs).isOk());
        EXPECT_FALSE(configs.empty());

        ::android::hardware::camera::device::V3_2::Stream hidlStreamConfig =
                Utils::makeToHidl(configs[0]);

        ::android::sp<hidlevs::V1_1::IEvsCamera> h0 =
                mEnumerator->openCamera_1_1(hidlCamera.v1.cameraId, hidlStreamConfig);
        ::android::sp<hidlevs::V1_1::IEvsCamera> h1 =
                mEnumerator->openCamera_1_1(hidlCamera.v1.cameraId, hidlStreamConfig);
        EXPECT_NE(nullptr, h0);
        EXPECT_NE(nullptr, h1);

        EXPECT_TRUE(mEnumerator->closeCamera(h1).isOk());
        EXPECT_TRUE(mEnumerator->closeCamera(h0).isOk());
    }
}

TEST_F(HidlEvsEnumeratorUnitTest, CloseInvalidEvsCamera) {
    ::android::sp<hidlevs::V1_1::IEvsCamera> invalidCamera;
    EXPECT_TRUE(mEnumerator->closeCamera(invalidCamera).isOk());
}

TEST_F(HidlEvsEnumeratorUnitTest, VerifyExclusiveDisplayOwner) {
    constexpr int kExclusiveMainDisplayId = 255;

    ::android::sp<hidlevs::V1_1::IEvsDisplay> success =
            mEnumerator->openDisplay_1_1(kExclusiveMainDisplayId);
    EXPECT_NE(nullptr, success);

    ::android::sp<hidlevs::V1_1::IEvsDisplay> failed = mEnumerator->openDisplay_1_1(/* id= */ 0);
    EXPECT_EQ(nullptr, failed);
}

TEST_F(HidlEvsEnumeratorUnitTest, VerifyUltrasonicsArray) {
    hidl_vec<hidlevs::V1_1::UltrasonicsArrayDesc> list;
    EXPECT_TRUE(
            mEnumerator->getUltrasonicsArrayList([&list](const auto& received) { list = received; })
                    .isOk());

    EXPECT_EQ(list.size(), 0);

    ::android::sp<hidlevs::V1_1::IEvsUltrasonicsArray> v =
            mEnumerator->openUltrasonicsArray(/* id= */ "invalidId");
    EXPECT_EQ(v, nullptr);
    EXPECT_TRUE(mEnumerator->closeUltrasonicsArray(v).isOk());
}

}  // namespace aidl::android::automotive::evs::implementation
