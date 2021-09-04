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

#ifndef PACKAGES_SCRIPTEXECUTOR_SRC_SCRIPTEXECUTORLISTENER_H_
#define PACKAGES_SCRIPTEXECUTOR_SRC_SCRIPTEXECUTORLISTENER_H_

#include "jni.h"

#include <string>

namespace com {
namespace android {
namespace car {
namespace scriptexecutor {

//  Wrapper class for IScriptExecutorListener.aidl.
class ScriptExecutorListener {
public:
    ScriptExecutorListener(JNIEnv* jni, jobject script_executor_listener);

    virtual ~ScriptExecutorListener();

    void onScriptFinished(jobject bundle);

    void onSuccess(jobject bundle);

    void onError(const int errorType, const char* message, const char* stackTrace);

    JNIEnv* getCurrentJNIEnv();

private:
    // Stores a jni global reference to Java Script Executor listener object.
    jobject mScriptExecutorListener;

    // Stores JavaVM pointer in order to be able to get JNIEnv pointer.
    // This is done because JNIEnv cannot be shared between threads.
    // https://developer.android.com/training/articles/perf-jni.html#javavm-and-jnienv
    JavaVM* mJavaVM;
};

}  // namespace scriptexecutor
}  // namespace car
}  // namespace android
}  // namespace com

#endif  // PACKAGES_SCRIPTEXECUTOR_SRC_SCRIPTEXECUTORLISTENER_H_
