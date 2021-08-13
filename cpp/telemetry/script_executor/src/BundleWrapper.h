/*
 * Copyright (c) 2021, The Android Open Source Project
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

#ifndef CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_BUNDLEWRAPPER_H_
#define CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_BUNDLEWRAPPER_H_

#include "jni.h"

namespace android {
namespace automotive {
namespace telemetry {
namespace script_executor {

// Used to create a java bundle object and populate its fields one at a time.
class BundleWrapper {
public:
    explicit BundleWrapper(JNIEnv* env);
    // BundleWrapper is not copyable.
    BundleWrapper(const BundleWrapper&) = delete;
    BundleWrapper& operator=(const BundleWrapper&) = delete;

    virtual ~BundleWrapper();

    // Family of methods that puts the provided 'value' into the Bundle under provided 'key'.
    void putBoolean(const char* key, bool value);
    void putInteger(const char* key, int value);
    void putDouble(const char* key, double value);
    void putString(const char* key, const char* value);

    jobject getBundle();

private:
    // The class asks Java to create Bundle object and stores the reference.
    // When the instance of this class is destroyed the actual Java Bundle object behind
    // this reference stays on and is managed by Java.
    jobject mBundle;

    // Reference to java Bundle class cached for performance reasons.
    jclass mBundleClass;

    // Stores a JNIEnv* pointer.
    JNIEnv* mJNIEnv;  // not owned
};

}  // namespace script_executor
}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SCRIPT_EXECUTOR_SRC_BUNDLEWRAPPER_H_
