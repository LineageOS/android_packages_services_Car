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

#define LOG "nativetelemetry_sample: "

#include "packages/services/Car/cpp/native_telemetry/proto/telemetry.pb.h"

#include <android-base/stringprintf.h>
#include <android/native/telemetry/BnNativeTelemetryService.h>
#include <android/native/telemetry/INativeTelemetryReportReadyListener.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <utils/Condition.h>
#include <utils/Log.h>
#include <utils/Mutex.h>

#include <getopt.h>
#include <stdlib.h>
#include <sysexits.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <iostream>
#include <memory>
#include <vector>

using ::android::base::StringPrintf;
using ::android::native::telemetry::INativeTelemetryService;

class NativeTelemetryReportReadyListenerImpl :
      public android::native::telemetry::BnNativeTelemetryReportReadyListener {
public:
    android::binder::Status onReady(const ::android::String16& metricConfigName) {
        std::cout << "NativeTelemetryReportReadyListenerImpl: Report ready for " << metricConfigName
                  << std::endl;
        return android::binder::Status::ok();
    }
};

int main(int argc, char* argv[]) {
    const std::string instance =
            StringPrintf("%s/default",
                         android::String8(INativeTelemetryService::descriptor).c_str());

    std::cout << "Obtaining: " << instance << std::endl;

    android::sp<android::native::telemetry::INativeTelemetryService> service =
            android::waitForService<android::native::telemetry::INativeTelemetryService>(
                    android::String16(instance.c_str()));

    if (!service) {
        std::cerr << "INativeTelemetryService service not found, may be still initializing?"
                  << std::endl;
        return EX_UNAVAILABLE;
    }

    android::sp<NativeTelemetryReportReadyListenerImpl> mReportReadyListener(
            new NativeTelemetryReportReadyListenerImpl);

    android::native::telemetry::MetricsConfig config;
    config.set_name("SampleMetric");
    config.set_version(2);
    config.set_script("Sample Script");

    std::string serialData;

    if (config.SerializeToString(&serialData)) {
        std::cout << "Successfully Serialized Data" << std::endl;
    } else {
        std::cout << "Unsuccessful in serializing data" << std::endl;
        return -1;
    }

    std::vector<uint8_t> vec(serialData.begin(), serialData.end());
    const std::vector<uint8_t>& c_vec = vec;

    service->addMetricsConfig(android::String16("TestConfig"), c_vec);

    service->setReportReadyListener(mReportReadyListener);

    std::cout << "Exiting" << std::endl;

    return EX_OK;
}
