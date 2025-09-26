#include <gtest/gtest.h>
#include <android-base/logging.h>
#include <android_car_feature.h>
#include <aidl/android/hardware/automotive/evs/CameraDesc.h>
#include <aidl/android/hardware/automotive/evs/IEvsCamera.h>
#include <aidl/android/hardware/automotive/evs/Stream.h>

#include "CompatEnumerator.h"
#include "NdkCameraManager.h"

namespace android::hardware::automotive::evs::compat {

using ::aidl::android::hardware::automotive::evs::CameraDesc;
using ::aidl::android::hardware::automotive::evs::IEvsCamera;
using ::aidl::android::hardware::automotive::evs::Stream;
using ::ndk::ScopedAStatus;

class CompatEnumeratorIntegrationTest : public ::testing::Test {
protected:
    void SetUp() override {
        // Ensure the feature is enabled for this test
        if (!android::car::feature::car_evs_compat_lib()) {
            LOG(WARNING) << "EVS compatibility library feature is not enabled, skipping test.";
            GTEST_SKIP();
        }
        enumerator = ::ndk::SharedRefBase::make<CompatEnumerator>();
        ASSERT_NE(enumerator, nullptr);
    }

    std::shared_ptr<CompatEnumerator> enumerator;
};

TEST_F(CompatEnumeratorIntegrationTest, OpenAndCloseFirstAvailableCamera) {
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();

    if (cameraList.empty()) {
        LOG(WARNING) << "No cameras found, skipping openCamera test.";
        GTEST_SKIP();
    }

    const std::string& cameraId = cameraList[0].id;
    LOG(INFO) << "Attempting to open camera: " << cameraId;

    Stream streamCfg; // Default stream config
    std::shared_ptr<IEvsCamera> camera;
    status = enumerator->openCamera(cameraId, streamCfg, &camera);
    EXPECT_TRUE(status.isOk()) << "Failed to open camera " << cameraId << ": "
                               << status.getDescription();
    EXPECT_NE(camera, nullptr) << "openCamera returned null for " << cameraId;

    if (camera) {
        LOG(INFO) << "Successfully opened camera: " << cameraId;
        // TODO: close camera and verify close success.
        // status = enumerator->closeCamera(camera);
        // EXPECT_TRUE(status.isOk())
    }
}

TEST_F(CompatEnumeratorIntegrationTest, OpenAllAvailableCameras) {
    std::vector<CameraDesc> cameraList;
    ScopedAStatus status = enumerator->getCameraList(&cameraList);
    ASSERT_TRUE(status.isOk()) << status.getDescription();

    if (cameraList.empty()) {
        LOG(WARNING) << "No cameras found, skipping openAllCameras test.";
        GTEST_SKIP();
    }

    for (const auto& desc : cameraList) {
        const std::string& cameraId = desc.id;
        LOG(INFO) << "Attempting to open camera: " << cameraId;

        Stream streamCfg; // Default stream config
        std::shared_ptr<IEvsCamera> camera;
        status = enumerator->openCamera(cameraId, streamCfg, &camera);
        EXPECT_TRUE(status.isOk()) << "Failed to open camera " << cameraId << ": "
                                   << status.getDescription();
        EXPECT_NE(camera, nullptr) << "openCamera returned null for " << cameraId;

        if (camera) {
            LOG(INFO) << "Successfully opened camera: " << cameraId;
            // TODO: close camera and verify close success.
            // status = enumerator->closeCamera(camera);
            // EXPECT_TRUE(status.isOk())
        }
    }
}

TEST_F(CompatEnumeratorIntegrationTest, OpenInvalidCamera) {
    std::string invalidCameraId = "invalidCameraId";
    Stream streamCfg;
    std::shared_ptr<IEvsCamera> camera;
    ScopedAStatus status = enumerator->openCamera(invalidCameraId, streamCfg, &camera);
    EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);

    EXPECT_EQ(camera, nullptr);
}
}  // namespace android::hardware::automotive::evs::compat
