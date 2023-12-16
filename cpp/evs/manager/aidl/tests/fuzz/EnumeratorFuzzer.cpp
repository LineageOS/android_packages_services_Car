/*
 * Copyright (C) 2022 The Android Open Source Project
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
#include "ServiceNames.h"

#include <android/binder_process.h>
#include <fuzzbinder/libbinder_ndk_driver.h>
#include <fuzzer/FuzzedDataProvider.h>
#include <utils/StrongPointer.h>

using ::aidl::android::automotive::evs::implementation::Enumerator;
using ::android::fuzzService;
using ::ndk::SharedRefBase;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    ABinderProcess_setThreadPoolMaxThreadCount(1);

    std::shared_ptr<Enumerator> aidlService = ::ndk::SharedRefBase::make<Enumerator>();
    if (!aidlService->init(kHardwareEnumeratorName)) {
        exit(1);
    }
    fuzzService(aidlService->asBinder().get(), FuzzedDataProvider(data, size));

    return 0;
}
