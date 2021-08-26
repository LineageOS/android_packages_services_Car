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

#include "ScriptExecutorListener.h"

#include <android-base/logging.h>

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

ScriptExecutorListener::~ScriptExecutorListener() {
    JNIEnv* env = getCurrentJNIEnv();
    if (mScriptExecutorListener != nullptr) {
        env->DeleteGlobalRef(mScriptExecutorListener);
    }
}

ScriptExecutorListener::ScriptExecutorListener(JNIEnv* env, jobject script_executor_listener) {
    mScriptExecutorListener = env->NewGlobalRef(script_executor_listener);
    env->GetJavaVM(&mJavaVM);
}

void ScriptExecutorListener::onSuccess(jobject bundle) {
    JNIEnv* env = getCurrentJNIEnv();
    if (mScriptExecutorListener == nullptr) {
        env->FatalError(
                "mScriptExecutorListener must point to a valid listener object, not nullptr.");
    }
    jclass listenerClass = env->GetObjectClass(mScriptExecutorListener);
    jmethodID onSuccessMethod =
            env->GetMethodID(listenerClass, "onSuccess", "(Landroid/os/Bundle;)V");
    env->CallVoidMethod(mScriptExecutorListener, onSuccessMethod, bundle);
}

void ScriptExecutorListener::onError(const int errorType, const char* message,
                                     const char* stackTrace) {
    JNIEnv* env = getCurrentJNIEnv();
    if (mScriptExecutorListener == nullptr) {
        env->FatalError(
                "mScriptExecutorListener must point to a valid listener object, not nullptr.");
    }
    jclass listenerClass = env->GetObjectClass(mScriptExecutorListener);
    jmethodID onErrorMethod =
            env->GetMethodID(listenerClass, "onError", "(ILjava/lang/String;Ljava/lang/String;)V");

    env->CallVoidMethod(mScriptExecutorListener, onErrorMethod, errorType,
                        env->NewStringUTF(message), env->NewStringUTF(stackTrace));
}

JNIEnv* ScriptExecutorListener::getCurrentJNIEnv() {
    JNIEnv* env;
    if (mJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG(FATAL) << "Unable to return JNIEnv from JavaVM";
    }
    return env;
}

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com
