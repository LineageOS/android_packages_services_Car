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

ScriptExecutorListener::ScriptExecutorListener(JNIEnv* env, jobject scriptExecutorListener) {
    mScriptExecutorListener = env->NewGlobalRef(scriptExecutorListener);
    env->GetJavaVM(&mJavaVM);
}

void ScriptExecutorListener::onSuccess(jobject bundle) {
    JNIEnv* env = getCurrentJNIEnv();
    jclass listenerClass = env->GetObjectClass(mScriptExecutorListener);
    jmethodID onSuccessMethod =
            env->GetMethodID(listenerClass, "onSuccess", "(Landroid/os/PersistableBundle;)V");
    env->CallVoidMethod(mScriptExecutorListener, onSuccessMethod, bundle);
}

void ScriptExecutorListener::onScriptFinished(jobject bundle) {
    JNIEnv* env = getCurrentJNIEnv();
    jclass listenerClass = env->GetObjectClass(mScriptExecutorListener);
    jmethodID onScriptFinished = env->GetMethodID(listenerClass, "onScriptFinished",
                                                  "(Landroid/os/PersistableBundle;)V");
    env->CallVoidMethod(mScriptExecutorListener, onScriptFinished, bundle);
}

void ScriptExecutorListener::onError(const ErrorType errorType, const char* message,
                                     const char* stackTrace) {
    JNIEnv* env = getCurrentJNIEnv();
    jclass listenerClass = env->GetObjectClass(mScriptExecutorListener);
    jmethodID onErrorMethod =
            env->GetMethodID(listenerClass, "onError", "(ILjava/lang/String;Ljava/lang/String;)V");

    env->CallVoidMethod(mScriptExecutorListener, onErrorMethod, static_cast<int>(errorType),
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
