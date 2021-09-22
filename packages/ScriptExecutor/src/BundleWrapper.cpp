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

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

BundleWrapper::BundleWrapper(JNIEnv* env) {
    mJNIEnv = env;
    mBundleClass = static_cast<jclass>(
            mJNIEnv->NewGlobalRef(mJNIEnv->FindClass("android/os/PersistableBundle")));
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
    // TODO(b/188832769): consider caching the references.
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
    // TODO(b/201008922): Handle a case when NewStringUTF returns nullptr (fails
    // to create a string).
    mJNIEnv->CallVoidMethod(mBundle, putStringMethod, mJNIEnv->NewStringUTF(key),
                            mJNIEnv->NewStringUTF(value));
}

void BundleWrapper::putLongArray(const char* key, const std::vector<int64_t>& value) {
    jmethodID putLongArrayMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putLongArray", "(Ljava/lang/String;[J)V");

    jlongArray array = mJNIEnv->NewLongArray(value.size());
    mJNIEnv->SetLongArrayRegion(array, 0, value.size(), &value[0]);
    mJNIEnv->CallVoidMethod(mBundle, putLongArrayMethod, mJNIEnv->NewStringUTF(key), array);
}

void BundleWrapper::putStringArray(const char* key, const std::vector<std::string>& value) {
    jmethodID putStringArrayMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putStringArray",
                                 "(Ljava/lang/String;[Ljava/lang/String;)V");

    jobjectArray array =
            mJNIEnv->NewObjectArray(value.size(), mJNIEnv->FindClass("java/lang/String"), nullptr);
    // TODO(b/201008922): Handle a case when NewStringUTF returns nullptr (fails
    // to create a string).
    for (int i = 0; i < value.size(); i++) {
        mJNIEnv->SetObjectArrayElement(array, i, mJNIEnv->NewStringUTF(value[i].c_str()));
    }
    mJNIEnv->CallVoidMethod(mBundle, putStringArrayMethod, mJNIEnv->NewStringUTF(key), array);
}

jobject BundleWrapper::getBundle() {
    return mBundle;
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
