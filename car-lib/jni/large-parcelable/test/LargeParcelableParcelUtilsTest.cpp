/*
 * Copyright 2025 The Android Open Source Project
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

#include <android-base/result.h>
#include <binder/RpcSession.h>
#include <cutils/ashmem.h>
#include <gtest/gtest.h>

#include <ParcelUtils.h>

#include <memory>

namespace android::jni::largeparcelable {

using ::android::Parcel;

class LargeParcelableParcelUtilsTest : public ::testing::Test {
public:
    void SetUp() override {
        for (size_t i = 0; i < mDataSze; i++) {
            mTestData.push_back(static_cast<uint8_t>(1));
        }
    }

protected:
    size_t mDataSze = 1024;
    std::vector<uint8_t> mTestData;
};

TEST_F(LargeParcelableParcelUtilsTest, TestMarshall) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);

    std::vector<uint8_t> buffer;
    buffer.resize(parcel->dataSize());

    ASSERT_RESULT_OK(marshall(parcel.get(), buffer.data(), parcel->dataSize()));

    std::unique_ptr<Parcel> parcel2 = std::make_unique<Parcel>();

    std::vector<uint8_t> out_data;

    parcel2->setData(buffer.data(), buffer.size());
    parcel2->setDataPosition(0);
    parcel2->readByteVector(&out_data);

    ASSERT_EQ(out_data, mTestData) << "Unmarshalled data must be equal to the original data";
}

TEST_F(LargeParcelableParcelUtilsTest, TestMarshall_error_nullParcel) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);

    std::vector<uint8_t> buffer;
    buffer.resize(parcel->dataSize());

    auto result = marshall(nullptr, buffer.data(), parcel->dataSize());

    ASSERT_FALSE(result.ok());
}

TEST_F(LargeParcelableParcelUtilsTest, TestMarshall_error_parcelForRpc) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    auto rpcSession = RpcSession::make();
    parcel->markForRpc(rpcSession);

    std::vector<uint8_t> buffer;
    buffer.resize(mDataSze);

    auto result = marshall(parcel.get(), buffer.data(), mDataSze);

    ASSERT_FALSE(result.ok());
}

TEST_F(LargeParcelableParcelUtilsTest, TestMarshall_error_parcelWithFd) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    int fd = ashmem_create_region("SharedMemory", mDataSze);
    ASSERT_TRUE(fd >= 0) << "Failed to create shared memory fd for testing";
    parcel->writeFileDescriptor(fd, /*takeOwnerShip=*/false);

    std::vector<uint8_t> buffer;
    buffer.resize(mDataSze);

    auto result = marshall(parcel.get(), buffer.data(), mDataSze);

    close(fd);

    ASSERT_FALSE(result.ok());
}

TEST_F(LargeParcelableParcelUtilsTest, TestMarshall_error_wrongDataSize) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);

    std::vector<uint8_t> buffer;
    buffer.resize(parcel->dataSize());

    auto result = marshall(parcel.get(), buffer.data(), parcel->dataSize() - 1);

    ASSERT_FALSE(result.ok());
}

TEST_F(LargeParcelableParcelUtilsTest, TestUnmarshall) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);

    const uint8_t* parcelData = parcel->data();
    size_t parcelDataSize = parcel->dataSize();

    std::unique_ptr<Parcel> parcel2 = std::make_unique<Parcel>();

    ASSERT_RESULT_OK(
            unmarshall(static_cast<const void*>(parcelData), parcelDataSize, parcel2.get()));

    std::vector<uint8_t> out_data;

    parcel2->setDataPosition(0);
    parcel2->readByteVector(&out_data);

    ASSERT_EQ(out_data, mTestData) << "Unmarshalled data must be equal to the original data";
}

TEST_F(LargeParcelableParcelUtilsTest, TestUnmarshall_error_nullParcel) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);
    const uint8_t* parcelData = parcel->data();
    size_t parcelDataSize = parcel->dataSize();

    auto result = unmarshall(static_cast<const void*>(parcelData), parcelDataSize, nullptr);

    ASSERT_FALSE(result.ok());
}

TEST_F(LargeParcelableParcelUtilsTest, TestUnmarshall_error_invalidSize) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    parcel->writeByteVector(mTestData);
    const uint8_t* parcelData = parcel->data();
    std::unique_ptr<Parcel> parcel2 = std::make_unique<Parcel>();

    auto result = unmarshall(static_cast<const void*>(parcelData), -1, parcel2.get());

    ASSERT_FALSE(result.ok());
}

}  // namespace android::jni::largeparcelable
