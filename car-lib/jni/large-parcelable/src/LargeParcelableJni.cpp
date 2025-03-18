/*
 * Copyright 2025 The Android Open Source Project
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

#define LOG_TAG "LargeParcelableJni"

#include "ParcelUtils.h"

#include <android-base/logging.h>
#include <binder/Parcel.h>
#include <nativehelper/JNIHelp.h>

#include <jni.h>

namespace {

using ::android::Parcel;
using ::android::jni::largeparcelable::marshall;
using ::android::jni::largeparcelable::unmarshall;

constexpr jint kJniVersion = JNI_VERSION_1_6;
constexpr const char kClassName[] = "com/android/car/internal/LargeParcelableBase";

// This method is similar to android_os_Parcel_marshall method used in core binder JNI, except
// that this method takes a ByteBuffer backed by DirectByteBuffer instead of byte array as input.
void marshallParcelToBuffer(JNIEnv* env, jclass clazz, jlong parcelNativePtr, jobject buffer,
                                   jint size) {
    void* bufferAddr = env->GetDirectBufferAddress(buffer);
    const Parcel* parcel = reinterpret_cast<const Parcel*>(parcelNativePtr);
    auto result = marshall(parcel, bufferAddr, size);
    if (!result.ok()) {
        jniThrowException(env, "java/lang/IllegalStateException", result.error().message().c_str());
    }
}

// This method is similar to android_os_Parcel_unmarshall method used in core binder JNI, except
// that this method takes a ByteBuffer backed by DirectByteBuffer instead of byte array as input.
void unmarshallBufferToParcel(JNIEnv* env, jclass clazz, jobject buffer, jint size,
                                     jlong parcelNativePtr) {
    const void* bufferAddr = env->GetDirectBufferAddress(buffer);
    Parcel* parcel = reinterpret_cast<Parcel*>(parcelNativePtr);
    auto result = unmarshall(bufferAddr, size, parcel);
    if (!result.ok()) {
        jniThrowException(env, "java/lang/IllegalStateException", result.error().message().c_str());
    }
}

const JNINativeMethod METHODS[] = {
        // nativeUnmarshall in android_os_Parcel.cpp is not FastNative, so we also do not
        // add FastNative here.
        //
        // private static native void nativeMarshallParcelToBuffer(
        //      long parcelNativePtr, ByteBuffer buffer, int size);
        {"nativeMarshallParcelToBuffer", "(JLjava/nio/ByteBuffer;I)V",
         reinterpret_cast<void*>(marshallParcelToBuffer)},
        // private static native void nativeUnmarshallBufferToParcel(
        //      ByteBuffer buffer, int size, long parcelNativePtr);
        {"nativeUnmarshallBufferToParcel", "(Ljava/nio/ByteBuffer;IJ)V",
         reinterpret_cast<void*>(unmarshallBufferToParcel)},
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), kJniVersion) != JNI_OK) {
        LOG(ERROR) << __FUNCTION__ << ": Failed to get the environment.";
        return JNI_ERR;
    }

    jniRegisterNativeMethods(env, kClassName, METHODS, sizeof(METHODS) / sizeof(JNINativeMethod));

    return kJniVersion;
}
