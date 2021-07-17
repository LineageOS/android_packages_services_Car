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

#include "BundleWrapper.h"

#include <android-base/logging.h>
#include <android_runtime/AndroidRuntime.h>

namespace android {
namespace automotive {
namespace telemetry {
namespace script_executor {

BundleWrapper::BundleWrapper(JNIEnv* env) {
    mJNIEnv = env;
    mBundleClass =
            static_cast<jclass>(mJNIEnv->NewGlobalRef(mJNIEnv->FindClass("android/os/Bundle")));
    jmethodID bundleConstructor = mJNIEnv->GetMethodID(mBundleClass, "<init>", "()V");
    mBundle = mJNIEnv->NewGlobalRef(mJNIEnv->NewObject(mBundleClass, bundleConstructor));
}

BundleWrapper::~BundleWrapper() {
    // Delete global JNI references.
    if (mBundle != NULL) {
        mJNIEnv->DeleteGlobalRef(mBundle);
    }
    if (mBundleClass != NULL) {
        mJNIEnv->DeleteGlobalRef(mBundleClass);
    }
}

void BundleWrapper::putBoolean(const char* key, bool value) {
    jmethodID putBooleanMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putBoolean", "(Ljava/lang/String;Z)V");
    mJNIEnv->CallVoidMethod(mBundle, putBooleanMethod, mJNIEnv->NewStringUTF(key),
                            static_cast<jboolean>(value));
}

void BundleWrapper::putInteger(const char* key, int value) {
    jmethodID putIntMethod = mJNIEnv->GetMethodID(mBundleClass, "putInt", "(Ljava/lang/String;I)V");
    mJNIEnv->CallVoidMethod(mBundle, putIntMethod, mJNIEnv->NewStringUTF(key),
                            static_cast<jint>(value));
}

void BundleWrapper::putDouble(const char* key, double value) {
    jmethodID putDoubleMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putDouble", "(Ljava/lang/String;D)V");
    mJNIEnv->CallVoidMethod(mBundle, putDoubleMethod, mJNIEnv->NewStringUTF(key),
                            static_cast<jdouble>(value));
}

void BundleWrapper::putString(const char* key, const char* value) {
    jmethodID putStringMethod = mJNIEnv->GetMethodID(mBundleClass, "putString",
                                                     "(Ljava/lang/String;Ljava/lang/String;)V");
    mJNIEnv->CallVoidMethod(mBundle, putStringMethod, mJNIEnv->NewStringUTF(key),
                            mJNIEnv->NewStringUTF(value));
}

jobject BundleWrapper::getBundle() {
    return mBundle;
}

}  // namespace script_executor
}  // namespace telemetry
}  // namespace automotive
}  // namespace android
