/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "NativeTelemetryServer"

#include "NativeTelemetryServer.h"

#include <utils/Log.h>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::native::telemetry::INativeTelemetryReportListener;
using ::android::native::telemetry::INativeTelemetryReportReadyListener;

NativeTelemetryServer::NativeTelemetryServer(LooperWrapper* looper) :
      mLooper(looper), mMessageHandler(new MessageHandlerImpl(this)) {
    ALOGI(LOG_TAG "Creating NativeTelemetryServer");
}

void NativeTelemetryServer::addMetricsConfig(const ::android::String16& metricsConfigName,
                                             const std::vector<uint8_t>& metricConfig) {
    const std::scoped_lock<std::mutex> lock(mMutex);
    android::native::telemetry::MetricsConfig config;
    bool parse = config.ParseFromArray(static_cast<const void*>(metricConfig.data()),
                                       metricConfig.size());
    if (!parse) {
        ALOGI(LOG_TAG "Failed to add config");
        // TODO: Return correct error codes
        return;
    }
    mActiveConfigs[config.name()] = config;

    ALOGI(LOG_TAG "Metric name: %s", config.name().c_str());
    ALOGI(LOG_TAG "Metric version: %d", config.version());
    ALOGI(LOG_TAG "Metric script: %s", config.script().c_str());
    ALOGI(LOG_TAG "Metric subs: %d", config.subscribers_size());
}

void NativeTelemetryServer::removeMetricsConfig(const ::android::String16& metricConfigName) {
    const std::scoped_lock<std::mutex> lock(mMutex);
    ALOGI(LOG_TAG "removing config");

    std::string key = android::String8(metricConfigName).c_str();
    if (mActiveConfigs.count(key)) {
        mActiveConfigs.erase(key);
    }
}

void NativeTelemetryServer::removeAllMetricsConfigs() {
    ALOGI(LOG_TAG "removing all metrics configs");
    // TODO: implement this function
}

void NativeTelemetryServer::getFinishedReport(
        const ::android::String16& metricConfigName,
        const std::shared_ptr<INativeTelemetryReportListener>& listener) {
    ALOGI(LOG_TAG "getFinishedReport");
    // TODO: implement this function
}

void NativeTelemetryServer::setReportReadyListener(
        const ::android::sp<
                ::android::native::telemetry::INativeTelemetryReportReadyListener>&
                listener) {
    const std::scoped_lock<std::mutex> lock(mMutex);

    ALOGI(LOG_TAG "setReportReadyListener");
    android::binder::Status status = listener->onReady(android::String16("Testing listener"));

    if (status.isOk()) {
        ALOGI(LOG_TAG "callback called successfully");
    } else {
        ALOGI(LOG_TAG "callback failed");
    }
}

void NativeTelemetryServer::clearReportReadyListener() {
    const std::scoped_lock<std::mutex> lock(mMutex);
    ALOGI(LOG_TAG "clearReportReadyListener");
}

NativeTelemetryServer::MessageHandlerImpl::MessageHandlerImpl(NativeTelemetryServer* server) :
      mNativeTelemetryServer(server) {}

void NativeTelemetryServer::MessageHandlerImpl::handleMessage(const Message& message) {}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android