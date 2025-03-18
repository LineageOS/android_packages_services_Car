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

#include <gtest/gtest.h>

#include <ParcelUtils.h>

#include <memory>

namespace android::jni::largeparcelable {

using ::android::Parcel;

TEST(LargeParcelableParcelUtilsTest, TestUnmarshall) {
    std::unique_ptr<Parcel> parcel = std::make_unique<Parcel>();
    std::vector<uint8_t> test_data;
    size_t test_data_size = 1024;
    for (size_t i = 0; i < test_data_size; i++) {
        test_data.push_back(static_cast<uint8_t>(1));
    }
    parcel->writeByteVector(test_data);

    const uint8_t* parcel_data = parcel->data();
    size_t parcel_data_size = parcel->dataSize();

    std::unique_ptr<Parcel> parcel2 = std::make_unique<Parcel>();

    unmarshall(static_cast<const void*>(parcel_data), parcel_data_size, parcel2.get());

    std::vector<uint8_t> out_data;

    parcel2->setDataPosition(0);
    parcel2->readByteVector(&out_data);

    ASSERT_EQ(test_data, out_data) << "Unmarshalled data must be equal to the original data";
}

}  // namespace android::jni::largeparcelable
