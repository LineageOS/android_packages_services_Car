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

#include "AidlVhalClient.h"

#include <AidlHalPropValue.h>
#include <ParcelableUtils.h>
#include <VehicleUtils.h>
#include <inttypes.h>

namespace android {
namespace frameworks {
namespace automotive {
namespace vhal {

using ::android::base::Error;
using ::android::base::Result;
using ::android::hardware::automotive::vehicle::fromStableLargeParcelable;
using ::android::hardware::automotive::vehicle::PendingRequestPool;
using ::android::hardware::automotive::vehicle::toInt;
using ::android::hardware::automotive::vehicle::vectorToStableLargeParcelable;

using ::aidl::android::hardware::automotive::vehicle::GetValueRequest;
using ::aidl::android::hardware::automotive::vehicle::GetValueRequests;
using ::aidl::android::hardware::automotive::vehicle::GetValueResult;
using ::aidl::android::hardware::automotive::vehicle::GetValueResults;
using ::aidl::android::hardware::automotive::vehicle::IVehicle;
using ::aidl::android::hardware::automotive::vehicle::SetValueResults;
using ::aidl::android::hardware::automotive::vehicle::StatusCode;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropErrors;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropValue;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropValues;

using ::ndk::ScopedAStatus;

AidlVhalClient::AidlVhalClient(std::shared_ptr<IVehicle> hal) : mHal(hal) {
    mGetSetValueClient = ndk::SharedRefBase::make<GetSetValueClient>(
            /*timeoutInNs=*/DEFAULT_TIMEOUT_IN_SEC * 1'000'000'000);
}

AidlVhalClient::AidlVhalClient(std::shared_ptr<IVehicle> hal, int64_t timeoutInMs) : mHal(hal) {
    mGetSetValueClient = ndk::SharedRefBase::make<GetSetValueClient>(
            /*timeoutInNs=*/timeoutInMs * 1'000'000);
}

void AidlVhalClient::getValue(const IHalPropValue& requestValue,
                              std::shared_ptr<GetValueCallbackFunc> callback) {
    int64_t requestId = mRequestId++;
    int32_t propId = requestValue.getPropId();
    int32_t areaId = requestValue.getAreaId();
    std::vector<GetValueRequest> requests = {
            {
                    .requestId = requestId,
                    .prop = *(reinterpret_cast<const VehiclePropValue*>(
                            requestValue.toVehiclePropValue())),
            },
    };

    GetValueRequests getValueRequests;
    ScopedAStatus status = vectorToStableLargeParcelable(std::move(requests), &getValueRequests);
    if (!status.isOk()) {
        mGetSetValueClient->tryFinishGetValueRequest(requestId);
        (*callback)(Error(status.getServiceSpecificError())
                    << "failed to serialize request for prop: " << propId << ", areaId: " << areaId
                    << ": error: " << status.getMessage());
    }

    mGetSetValueClient->addGetValueRequest(requestId, requestValue, callback);
    status = mHal->getValues(mGetSetValueClient, getValueRequests);
    if (!status.isOk()) {
        mGetSetValueClient->tryFinishGetValueRequest(requestId);
        (*callback)(Error(status.getServiceSpecificError())
                    << "failed to get value for prop: " << propId << ", areaId: " << areaId
                    << ": error: " << status.getMessage());
    }
}

void AidlVhalClient::setValue([[maybe_unused]] const IHalPropValue& value,
                              [[maybe_unused]] std::shared_ptr<SetValueCallbackFunc> callback) {
    // TODO(b/214635003): implement this.
}

StatusCode AidlVhalClient::linkToDeath(
        [[maybe_unused]] std::shared_ptr<OnBinderDiedCallbackFunc> callback) {
    // TODO(b/214635003): implement this.
    return StatusCode::OK;
}

StatusCode AidlVhalClient::unlinkToDeath(
        [[maybe_unused]] std::shared_ptr<OnBinderDiedCallbackFunc> callback) {
    // TODO(b/214635003): implement this.
    return StatusCode::OK;
}

Result<std::vector<std::unique_ptr<IHalPropConfig>>> AidlVhalClient::getAllPropConfigs() {
    // TODO(b/214635003): implement this.
    return {};
}

std::unique_ptr<ISubscriptionClient> AidlVhalClient::getSubscriptionClient(
        [[maybe_unused]] std::shared_ptr<ISubscriptionCallback> callback) {
    // TODO(b/214635003): implement this.
    return nullptr;
}

GetSetValueClient::GetSetValueClient(int64_t timeoutInNs) {
    mPendingRequestPool = std::make_unique<PendingRequestPool>(timeoutInNs);
    mOnGetValueTimeout = std::make_unique<PendingRequestPool::TimeoutCallbackFunc>(
            [this](const std::unordered_set<int64_t>& requestIds) {
                onGetValueTimeout(requestIds);
            });
}

GetSetValueClient::~GetSetValueClient() {
    // Delete the pending request pool, mark all pending request as timed-out.
    mPendingRequestPool.reset();
}

void GetSetValueClient::addGetValueRequest(
        int64_t requestId, const IHalPropValue& requestProp,
        std::shared_ptr<AidlVhalClient::GetValueCallbackFunc> callback) {
    std::lock_guard<std::mutex> lk(mLock);
    mPendingGetValueCallbacks[requestId] =
            std::make_unique<PendingGetValueRequest>(PendingGetValueRequest{
                    .callback = callback,
                    .propId = requestProp.getPropId(),
                    .areaId = requestProp.getAreaId(),
            });
    mPendingRequestPool->addRequests(/*clientId=*/nullptr, {requestId}, mOnGetValueTimeout);
}

std::unique_ptr<GetSetValueClient::PendingGetValueRequest>
GetSetValueClient::tryFinishGetValueRequest(int64_t requestId) {
    auto finished = mPendingRequestPool->tryFinishRequests(/*clientId=*/nullptr, {requestId});
    if (finished.empty()) {
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lk(mLock);
        auto it = mPendingGetValueCallbacks.find(requestId);
        if (it == mPendingGetValueCallbacks.end()) {
            return nullptr;
        }
        auto request = std::move(it->second);
        mPendingGetValueCallbacks.erase(requestId);
        return std::move(request);
    }
}

ScopedAStatus GetSetValueClient::onGetValues(const GetValueResults& results) {
    auto parcelableResult = fromStableLargeParcelable(results);
    if (!parcelableResult.ok()) {
        ALOGE("failed to parse GetValueResults returned from VHAL, error: %s",
              parcelableResult.error().getMessage());
        return std::move(parcelableResult.error());
    }
    for (const GetValueResult& result : parcelableResult.value().getObject()->payloads) {
        onGetValue(result);
    }
    return ScopedAStatus::ok();
}

void GetSetValueClient::onGetValue(const GetValueResult& result) {
    int64_t requestId = result.requestId;

    auto pendingRequest = tryFinishGetValueRequest(requestId);
    if (pendingRequest == nullptr) {
        ALOGD("failed to find pending request for ID: %" PRId64 ", maybe already timed-out",
              requestId);
        return;
    }

    std::shared_ptr<AidlVhalClient::GetValueCallbackFunc> callback = pendingRequest->callback;
    int32_t propId = pendingRequest->propId;
    int32_t areaId = pendingRequest->areaId;
    if (result.status != StatusCode::OK) {
        int status = toInt(result.status);
        (*callback)(Error(status) << "failed to get value for propId: " << propId
                                  << ", areaId: " << areaId << ": status: " << status);
    } else if (!result.prop.has_value()) {
        (*callback)(Error(toInt(StatusCode::INTERNAL_ERROR))
                    << "failed to get value for propId: " << propId << ", areaId: " << areaId
                    << ": returns no value");
    } else {
        VehiclePropValue valueCopy = result.prop.value();
        std::unique_ptr<IHalPropValue> propValue =
                std::make_unique<AidlHalPropValue>(std::move(valueCopy));
        (*callback)(std::move(propValue));
    }
}

ScopedAStatus GetSetValueClient::onSetValues([[maybe_unused]] const SetValueResults& results) {
    // TODO(b/214635003): implement this.
    return ScopedAStatus::ok();
}

ScopedAStatus GetSetValueClient::onPropertyEvent([[maybe_unused]] const VehiclePropValues&,
                                                 int32_t) {
    // TODO(b/214635003): implement this.
    return ScopedAStatus::ok();
}

ScopedAStatus GetSetValueClient::onPropertySetError([[maybe_unused]] const VehiclePropErrors&) {
    // TODO(b/214635003): implement this.
    return ScopedAStatus::ok();
}

void GetSetValueClient::onGetValueTimeout(const std::unordered_set<int64_t>& requestIds) {
    for (int64_t requestId : requestIds) {
        std::unique_ptr<PendingGetValueRequest> pendingRequest;
        {
            std::lock_guard<std::mutex> lk(mLock);
            auto it = mPendingGetValueCallbacks.find(requestId);
            if (it == mPendingGetValueCallbacks.end()) {
                ALOGW("failed to find the timed-out pending request for ID: %" PRId64 ", ignore",
                      requestId);
                continue;
            }
            pendingRequest = std::move(it->second);
            mPendingGetValueCallbacks.erase(requestId);
        }

        (*pendingRequest->callback)(Error(toInt(StatusCode::TRY_AGAIN))
                                    << "failed to get value for propId: " << pendingRequest->propId
                                    << ", areaId: " << pendingRequest->areaId
                                    << ": request timed out");
    }
}

}  // namespace vhal
}  // namespace automotive
}  // namespace frameworks
}  // namespace android
