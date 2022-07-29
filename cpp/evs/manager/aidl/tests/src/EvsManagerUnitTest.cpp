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

namespace {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::DisplayDesc;
using ::aidl::android::hardware::automotive::evs::DisplayState;
using ::aidl::android::hardware::automotive::evs::IEvsCamera;
using ::aidl::android::hardware::automotive::evs::IEvsDisplay;
using ::aidl::android::hardware::automotive::evs::IEvsEnumerator;
using ::aidl::android::hardware::automotive::evs::IEvsEnumeratorStatusCallback;
using ::aidl::android::hardware::automotive::evs::IEvsUltrasonicsArray;
using ::aidl::android::hardware::automotive::evs::Stream;

constexpr size_t kNumMockEvsCameras = 4;
constexpr size_t kNumMockEvsDisplays = 2;

}  // namespace

namespace aidl::android::automotive::evs::implementation {

class EvsEnumeratorUnitTest : public ::testing::Test {
public:
    EvsEnumeratorUnitTest() {
        // Instantiates IEvsEnumerator
        mEnumerator = ndk::SharedRefBase::make<Enumerator>();
        EXPECT_NE(nullptr, mEnumerator);

        // Disable a permission check
        mEnumerator->enablePermissionCheck(/* enable= */ false);
    }

    ~EvsEnumeratorUnitTest() override {
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
        mEnumerator->init(hwEnumerator, /* enableMonitor= */ true);
    }

    void TearDown() override {
        // This will be called immediately after each test; right before the
        // destructor.
    }

protected:
    // Class members declared here can be used by all tests in the test suite
    // for EvsEnumerator
    std::shared_ptr<Enumerator> mEnumerator;

private:
    std::shared_ptr<MockEvsHal> mMockEvsHal;
};

TEST_F(EvsEnumeratorUnitTest, VerifyPermissionCheck) {
    mEnumerator->enablePermissionCheck(true);

    std::vector<CameraDesc> cameras;
    EXPECT_FALSE(mEnumerator->getCameraList(&cameras).isOk());

    std::shared_ptr<IEvsCamera> invalidCamera;
    Stream emptyConfig;
    EXPECT_FALSE(mEnumerator->openCamera(/* cameraId= */ "invalidId", emptyConfig, &invalidCamera)
                         .isOk());
    EXPECT_EQ(nullptr, invalidCamera);
    EXPECT_FALSE(mEnumerator->closeCamera(invalidCamera).isOk());

    std::shared_ptr<IEvsDisplay> invalidDisplay;
    EXPECT_FALSE(mEnumerator->openDisplay(/* displayId= */ 0xFF, &invalidDisplay).isOk());

    DisplayState emptyState;
    EXPECT_FALSE(mEnumerator->getDisplayState(&emptyState).isOk());

    mEnumerator->enablePermissionCheck(false);
}

TEST_F(EvsEnumeratorUnitTest, VerifyIsHardwareMethod) {
    bool isHardware = true;

    EXPECT_TRUE(mEnumerator->isHardware(&isHardware).isOk());

    EXPECT_FALSE(isHardware);
}

TEST_F(EvsEnumeratorUnitTest, VerifyOpenAndCloseDisplay) {
    std::vector<uint8_t> displays;

    EXPECT_TRUE(mEnumerator->getDisplayIdList(&displays).isOk());
    EXPECT_EQ(kNumMockEvsDisplays, displays.size());

    for (auto& id : displays) {
        std::shared_ptr<IEvsDisplay> h0, h1;
        EXPECT_TRUE(mEnumerator->openDisplay(id, &h0).isOk());
        EXPECT_NE(nullptr, h0);

        EXPECT_TRUE(mEnumerator->openDisplay(id, &h1).isOk());
        EXPECT_NE(nullptr, h1);

        DisplayDesc desc;
        EXPECT_TRUE(h1->getDisplayInfo(&desc).isOk());

        DisplayState state;
        EXPECT_TRUE(mEnumerator->getDisplayState(&state).isOk());
        EXPECT_EQ(DisplayState::NOT_VISIBLE, state);

        EXPECT_TRUE(mEnumerator->closeDisplay(h1).isOk());

        // closeDisplay() with an invalidated display handle should be okay.
        EXPECT_TRUE(mEnumerator->closeDisplay(h0).isOk());
    }
}

TEST_F(EvsEnumeratorUnitTest, VerifyOpenAndCloseCamera) {
    std::vector<CameraDesc> cameras;

    EXPECT_TRUE(mEnumerator->getCameraList(&cameras).isOk());
    EXPECT_EQ(kNumMockEvsCameras, cameras.size());

    for (auto& desc : cameras) {
        std::vector<Stream> configs;

        EXPECT_TRUE(mEnumerator->getStreamList(desc, &configs).isOk());
        EXPECT_FALSE(configs.empty());

        std::shared_ptr<IEvsCamera> h0, h1;
        EXPECT_TRUE(mEnumerator->openCamera(desc.id, configs[0], &h0).isOk());
        EXPECT_NE(nullptr, h0);
        EXPECT_TRUE(mEnumerator->openCamera(desc.id, configs[0], &h1).isOk());
        EXPECT_NE(nullptr, h1);

        EXPECT_TRUE(mEnumerator->closeCamera(h1).isOk());
        EXPECT_TRUE(mEnumerator->closeCamera(h0).isOk());
    }
}

TEST_F(EvsEnumeratorUnitTest, CloseInvalidEvsCamera) {
    std::shared_ptr<IEvsCamera> invalidCamera;
    EXPECT_FALSE(mEnumerator->closeCamera(invalidCamera).isOk());
}

TEST_F(EvsEnumeratorUnitTest, VerifyExclusiveDisplayOwner) {
    constexpr int kExclusiveMainDisplayId = 255;
    std::shared_ptr<IEvsDisplay> display;
    EXPECT_TRUE(mEnumerator->openDisplay(kExclusiveMainDisplayId, &display).isOk());
    EXPECT_NE(nullptr, display);

    std::shared_ptr<IEvsDisplay> failed;
    EXPECT_FALSE(mEnumerator->openDisplay(/* displayId= */ 0, &failed).isOk());
}

TEST_F(EvsEnumeratorUnitTest, VerifyRegisterStatusCallback) {
    std::shared_ptr<::testing::NiceMock<IEvsEnumeratorStatusCallback>> cb;
    EXPECT_TRUE(mEnumerator->registerStatusCallback(cb).isOk());
}

TEST_F(EvsEnumeratorUnitTest, VerifyUltrasonicsArray) {
    EXPECT_FALSE(mEnumerator->getUltrasonicsArrayList(nullptr).isOk());
    EXPECT_FALSE(mEnumerator->openUltrasonicsArray(/* id= */ "invalidId", nullptr).isOk());

    std::shared_ptr<IEvsUltrasonicsArray> empty;
    EXPECT_FALSE(mEnumerator->closeUltrasonicsArray(empty).isOk());
}

}  // namespace aidl::android::automotive::evs::implementation
