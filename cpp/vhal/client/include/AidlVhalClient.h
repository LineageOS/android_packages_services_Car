/*
 * Copyright (c) 2022, The Android Open Source Project
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

#ifndef CPP_VHAL_CLIENT_INCLUDE_AIDLVHALCLIENT_H_
#define CPP_VHAL_CLIENT_INCLUDE_AIDLVHALCLIENT_H_

#include "IVhalClient.h"

#include <aidl/android/hardware/automotive/vehicle/BnVehicleCallback.h>
#include <aidl/android/hardware/automotive/vehicle/IVehicle.h>
#include <android-base/thread_annotations.h>

#include <PendingRequestPool.h>

#include <atomic>
#include <memory>
#include <mutex>  // NOLINT
#include <unordered_map>

namespace android {
namespace frameworks {
namespace automotive {
namespace vhal {

class GetSetValueClient;

class AidlVhalClient final : public IVhalClient {
public:
    explicit AidlVhalClient(
            std::shared_ptr<::aidl::android::hardware::automotive::vehicle::IVehicle> hal);

    AidlVhalClient(std::shared_ptr<::aidl::android::hardware::automotive::vehicle::IVehicle> hal,
                   int64_t timeoutInMs);

    void getValue(const IHalPropValue& requestValue,
                  std::shared_ptr<GetValueCallbackFunc> callback) override;

    void setValue(const IHalPropValue& value,
                  std::shared_ptr<AidlVhalClient::SetValueCallbackFunc> callback) override;

    ::aidl::android::hardware::automotive::vehicle::StatusCode linkToDeath(
            std::shared_ptr<OnBinderDiedCallbackFunc> callback) override;

    ::aidl::android::hardware::automotive::vehicle::StatusCode unlinkToDeath(
            std::shared_ptr<OnBinderDiedCallbackFunc> callback) override;

    ::android::base::Result<std::vector<std::unique_ptr<IHalPropConfig>>> getAllPropConfigs()
            override;

    std::unique_ptr<ISubscriptionClient> getSubscriptionClient(
            std::shared_ptr<ISubscriptionCallback> callback) override;

private:
    std::atomic<int64_t> mRequestId = 0;
    std::shared_ptr<GetSetValueClient> mGetSetValueClient;
    std::shared_ptr<::aidl::android::hardware::automotive::vehicle::IVehicle> mHal;
};

class GetSetValueClient final :
      public ::aidl::android::hardware::automotive::vehicle::BnVehicleCallback {
public:
    struct PendingGetValueRequest {
        std::shared_ptr<AidlVhalClient::GetValueCallbackFunc> callback;
        int32_t propId;
        int32_t areaId;
    };

    explicit GetSetValueClient(int64_t timeoutInNs);

    ~GetSetValueClient();

    ::ndk::ScopedAStatus onGetValues(
            const ::aidl::android::hardware::automotive::vehicle::GetValueResults& results)
            override;
    ::ndk::ScopedAStatus onSetValues(
            const ::aidl::android::hardware::automotive::vehicle::SetValueResults& results)
            override;
    ::ndk::ScopedAStatus onPropertyEvent(
            const ::aidl::android::hardware::automotive::vehicle::VehiclePropValues& values,
            int32_t sharedMemoryCount) override;
    ::ndk::ScopedAStatus onPropertySetError(
            const ::aidl::android::hardware::automotive::vehicle::VehiclePropErrors& errors)
            override;

    // Add a new pending getValue request.
    void addGetValueRequest(int64_t requestId, const IHalPropValue& requestValue,
                            std::shared_ptr<AidlVhalClient::GetValueCallbackFunc> callback);
    // Try to finish the pending getValue request according to the requestId. If there is an
    // existing pending request, the request would be finished and returned. Otherwise, if the
    // request has already timed-out, nullptr would be returned.
    std::unique_ptr<PendingGetValueRequest> tryFinishGetValueRequest(int64_t requestId);

private:
    std::mutex mLock;
    std::unordered_map<int64_t, std::unique_ptr<PendingGetValueRequest>> mPendingGetValueCallbacks
            GUARDED_BY(mLock);
    std::unique_ptr<hardware::automotive::vehicle::PendingRequestPool> mPendingRequestPool;
    std::shared_ptr<
            ::android::hardware::automotive::vehicle::PendingRequestPool::TimeoutCallbackFunc>
            mOnGetValueTimeout;

    void onGetValue(const ::aidl::android::hardware::automotive::vehicle::GetValueResult& result);
    void onGetValueTimeout(const std::unordered_set<int64_t>& requestIds);
};

}  // namespace vhal
}  // namespace automotive
}  // namespace frameworks
}  // namespace android

#endif  // CPP_VHAL_CLIENT_INCLUDE_AIDLVHALCLIENT_H_
