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


namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

using ::android::base::Error;
using ::android::base::Result;

namespace {

Result<jstring> TryCreateUTFString(JNIEnv* env, const char* string) {
    jstring utfString = env->NewStringUTF(string);
    if (env->ExceptionCheck()) {
        // NewStringUTF throws an exception if we run out of memory while creating a UTF string.
        return Error()
                << "NewStringUTF ran out of memory while converting a string provided by Lua.";
    }
    if (utfString == nullptr) {
        return Error()
                << "Failed to convert a Lua string into a modified UTF-8 string. Please verify "
                   "that the string returned by Lua is in proper Modified UTF-8 format.";
    }
    return utfString;
}

}  // namespace

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

Result<void> BundleWrapper::putBoolean(const char* key, bool value) {
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }

    // TODO(b/188832769): consider caching the references.
    jmethodID putBooleanMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putBoolean", "(Ljava/lang/String;Z)V");
    mJNIEnv->CallVoidMethod(mBundle, putBooleanMethod, keyStringResult.value(),
                            static_cast<jboolean>(value));
    return {};  // ok result
}

Result<void> BundleWrapper::putLong(const char* key, int64_t value) {
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }

    jmethodID putLongMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putLong", "(Ljava/lang/String;J)V");
    mJNIEnv->CallVoidMethod(mBundle, putLongMethod, keyStringResult.value(),
                            static_cast<jlong>(value));
    return {};  // ok result
}

Result<void> BundleWrapper::putDouble(const char* key, double value) {
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }

    jmethodID putDoubleMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putDouble", "(Ljava/lang/String;D)V");
    mJNIEnv->CallVoidMethod(mBundle, putDoubleMethod, keyStringResult.value(),
                            static_cast<jdouble>(value));
    return {};  // ok result
}

Result<void> BundleWrapper::putString(const char* key, const char* value) {
    jmethodID putStringMethod = mJNIEnv->GetMethodID(mBundleClass, "putString",
                                                     "(Ljava/lang/String;Ljava/lang/String;)V");
    // TODO(b/201008922): Handle a case when NewStringUTF returns nullptr (fails
    // to create a string).
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }
    auto valueStringResult = TryCreateUTFString(mJNIEnv, value);
    if (!valueStringResult.ok()) {
        return Error() << "Failed to create a string for value=" << value << ". "
                       << valueStringResult.error();
    }

    mJNIEnv->CallVoidMethod(mBundle, putStringMethod, keyStringResult.value(),
                            valueStringResult.value());
    return {};  // ok result
}

Result<void> BundleWrapper::putLongArray(const char* key, const std::vector<int64_t>& value) {
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }

    jmethodID putLongArrayMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putLongArray", "(Ljava/lang/String;[J)V");

    jlongArray array = mJNIEnv->NewLongArray(value.size());
    mJNIEnv->SetLongArrayRegion(array, 0, value.size(), &value[0]);
    mJNIEnv->CallVoidMethod(mBundle, putLongArrayMethod, keyStringResult.value(), array);
    return {};  // ok result
}

Result<void> BundleWrapper::putStringArray(const char* key, const std::vector<std::string>& value) {
    auto keyStringResult = TryCreateUTFString(mJNIEnv, key);
    if (!keyStringResult.ok()) {
        return Error() << "Failed to create a string for key=" << key << ". "
                       << keyStringResult.error();
    }

    jmethodID putStringArrayMethod =
            mJNIEnv->GetMethodID(mBundleClass, "putStringArray",
                                 "(Ljava/lang/String;[Ljava/lang/String;)V");

    jobjectArray array =
            mJNIEnv->NewObjectArray(value.size(), mJNIEnv->FindClass("java/lang/String"), nullptr);
    // TODO(b/201008922): Handle a case when NewStringUTF returns nullptr (fails
    // to create a string).
    for (int i = 0; i < value.size(); i++) {
        auto valueStringResult = TryCreateUTFString(mJNIEnv, value[i].c_str());
        if (!valueStringResult.ok()) {
            return Error() << "Failed to create a string for value=" << value[i].c_str() << ". "
                           << valueStringResult.error();
        }
        mJNIEnv->SetObjectArrayElement(array, i, valueStringResult.value());
    }
    mJNIEnv->CallVoidMethod(mBundle, putStringArrayMethod, keyStringResult.value(), array);
    return {};  // ok result
}

jobject BundleWrapper::getBundle() {
    return mBundle;
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
