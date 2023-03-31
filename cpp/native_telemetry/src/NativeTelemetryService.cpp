/*
 * Copyright (c) 2023, The Android Open Source Project
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

#define LOG_TAG "NativeTelemetryService"

#include "NativeTelemetryService.h"

namespace android {
namespace automotive {
namespace telemetry {

NativeTelemetryServiceImpl::NativeTelemetryServiceImpl(NativeTelemetryServer* server) :
      mNativeTelemetryServer(server) {
    ALOGD(LOG_TAG "Service Created");
}

android::binder::Status NativeTelemetryServiceImpl::addMetricsConfig(
        const ::android::String16& metricConfigName, const ::std::vector<uint8_t>& metricConfig) {
    ALOGI(LOG_TAG "adding config: %s", android::String8(metricConfigName).c_str());
    mNativeTelemetryServer->addMetricsConfig(metricConfigName, metricConfig);
    return android::binder::Status::ok();
}

android::binder::Status NativeTelemetryServiceImpl::removeMetricsConfig(
        const ::android::String16& metricConfigName) {
    mNativeTelemetryServer->removeMetricsConfig(metricConfigName);
    return android::binder::Status::ok();
}

android::binder::Status NativeTelemetryServiceImpl::removeAllMetricsConfigs() {
    ALOGI(LOG_TAG "removing all metrics configs");
    mNativeTelemetryServer->removeAllMetricsConfigs();
    return android::binder::Status::ok();
}

android::binder::Status NativeTelemetryServiceImpl::getFinishedReport(
        const ::android::String16& metricConfigName,
        const ::android::sp<
                ::android::native::telemetry::INativeTelemetryReportListener>& listener) {
    ALOGI(LOG_TAG "getFinishedReport");
    return android::binder::Status::ok();
}

android::binder::Status NativeTelemetryServiceImpl::setReportReadyListener(
        const ::android::sp<
                ::android::native::telemetry::INativeTelemetryReportReadyListener>&
                listener) {
    ALOGI(LOG_TAG "setReportReadyListener");
    mNativeTelemetryServer->setReportReadyListener(listener);
    return android::binder::Status::ok();
}

android::binder::Status NativeTelemetryServiceImpl::clearReportReadyListener() {
    ALOGI(LOG_TAG "clearReportReadyListener");
    return android::binder::Status::ok();
}
}  // namespace telemetry
}  // namespace automotive
}  // namespace android