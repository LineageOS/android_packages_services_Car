/*
 * Copyright 2021 The Android Open Source Project
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

#include "EvsServiceWrapper.h"

#include <android-base/chrono_utils.h>
#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <android_runtime/android_hardware_HardwareBuffer.h>
#include <nativehelper/JNIHelp.h>
#include <ui/GraphicBuffer.h>
#include <utils/Errors.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>
#include <vndk/hardware_buffer.h>

#include <jni.h>

#include <map>

using ::android::GraphicBuffer;
using ::android::Mutex;
using ::android::sp;
using ::android::automotive::evs::EvsServiceWrapper;

using namespace ::android::hardware::automotive::evs::V1_1;

namespace {

// CarEvsService class
constexpr const char* kCarEvsServiceClassName = "com/android/car/evs/CarEvsService";

struct serviceFields_t {
    // Cached JVM
    JavaVM* vm;

    // CarEvsService instance global reference
    jobject thiz;

    // An event death handler in Java, called after a native handler
    jmethodID postNativeEventHandler;

    // A frame death handler in Java, called after a native handler
    jmethodID postNativeFrameHandler;

    // A service death handler in Java, called after a native handler
    jmethodID postNativeDeathHandler;

    // Stores EvsServiceWrapper object in Java
    jfieldID nativeEvsServiceObj;
} carEvsSvcFields;

// Bookkeeps descriptors of received frame buffers.
std::mutex gRecordMutex;
std::map<int, BufferDesc> gBufferRecords GUARDED_BY(gRecordMutex);

inline jclass findClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
    return clazz;
}

inline jfieldID getFieldIdOrDie(JNIEnv* env, jclass clazz, const char* field_name,
                                const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find field %s with signature %s", field_name,
                        field_signature);
    return res;
}

jmethodID getMethodIDOrDie(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    jmethodID res = env->GetMethodID(clazz, name, signature);
    LOG_ALWAYS_FATAL_IF(res == nullptr, "Unable to find method %s with signature %s", name,
                        signature);

    return res;
}

JNIEnv* getJNIEnvironment(JavaVM* jvm, jint version = JNI_VERSION_1_4) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), version) != JNI_OK) {
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to get JNI Environment");
    }

    return env;
}

template <typename T>
inline T makeGlobalRefOrDie(JNIEnv* env, T in) {
    jobject res = env->NewGlobalRef(in);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to create global reference.");
    return static_cast<T>(res);
}

JNIEnv* getOrAttachJNIEnvironment(JavaVM* jvm, jint version = JNI_VERSION_1_4) {
    JNIEnv* env = getJNIEnvironment(jvm, version);
    if (!env) {
        int result = jvm->AttachCurrentThread(&env, nullptr);
        LOG_ALWAYS_FATAL_IF(result != JNI_OK, "JVM thread attach failed");

        struct VmDetacher {
            VmDetacher(JavaVM* jvm) : mVm(jvm) {}
            ~VmDetacher() { mVm->DetachCurrentThread(); }

        private:
            JavaVM* const mVm;
        };

        thread_local VmDetacher detacher(jvm);
    }

    return env;
}

/*
 * Releases a service handle
 */
inline void releaseServiceHandle(JNIEnv* env) {
    if (!env || !carEvsSvcFields.thiz) {
        return;
    }

    delete reinterpret_cast<EvsServiceWrapper*>(
            env->GetLongField(carEvsSvcFields.thiz, carEvsSvcFields.nativeEvsServiceObj));

    env->SetLongField(carEvsSvcFields.thiz, carEvsSvcFields.nativeEvsServiceObj,
                      static_cast<jlong>(0));
}

/*
 * Retrieves a service handle from an object stored in Java
 */
EvsServiceWrapper* getServiceHandleOrThrowException(JNIEnv* env) {
    if (!env) {
        jniThrowNullPointerException(env, "Got an invalid JNI environment");
        return nullptr;
    }

    if (carEvsSvcFields.thiz != nullptr) {
        return reinterpret_cast<EvsServiceWrapper*>(
                env->GetLongField(carEvsSvcFields.thiz, carEvsSvcFields.nativeEvsServiceObj));
    } else {
        // The service may not be initialized yet.
        return nullptr;
    }
}

/*
 * Handles an unexpected death of EVS service
 */
void handleServiceDied(const android::wp<android::hidl::base::V1_0::IBase>& who) {
    JNIEnv* env = getOrAttachJNIEnvironment(carEvsSvcFields.vm, JNI_VERSION_1_6);
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isEqual(who)) {
        // We're not interested in this death notification.
        return;
    }

    LOG(ERROR) << "EVS service has died.";
    // EVS service has died but CarEvsManager instance still alives.
    // Only a service handle needs to be destroyed; this will be
    // re-created when CarEvsManager successfully connects to EVS service
    // when it comes back.
    env->CallVoidMethod(carEvsSvcFields.thiz, carEvsSvcFields.postNativeDeathHandler);
    releaseServiceHandle(env);
}

/*
 * Connects to the Extended View System service
 */
jboolean connectToHalServiceIfNecessary(JNIEnv* env, jobject thiz) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (handle && handle->isServiceAvailable()) {
        LOG(DEBUG) << "Service is connected already.";
        return JNI_TRUE;
    }

    LOG(DEBUG) << "Connecting to EVS service";
    // Deletes an old service handle
    releaseServiceHandle(env);

    // Initializes a new service handle with a death handler
    handle = new EvsServiceWrapper();
    if (!handle->initialize(handleServiceDied)) {
        LOG(ERROR) << "Failed to initialize a service handle";
        delete handle;
        return JNI_FALSE;
    }

    // Caches the current caller instance
    if (thiz != carEvsSvcFields.thiz) {
        env->DeleteGlobalRef(carEvsSvcFields.thiz);
        carEvsSvcFields.thiz = env->NewGlobalRef(thiz);
        if (carEvsSvcFields.thiz == nullptr) {
            LOG(ERROR) << "Failed to create a global reference of a caller instance.";
            delete handle;
            return JNI_FALSE;
        }
    }

    // Stores an object in a variable managed in Java
    env->SetLongField(thiz, carEvsSvcFields.nativeEvsServiceObj, reinterpret_cast<jlong>(handle));

    return JNI_TRUE;
}

/*
 * Disconnects from the Extended View System service
 */
void disconnectFromHalService(JNIEnv* env, jobject /*thiz*/) {
    releaseServiceHandle(env);
    if (carEvsSvcFields.thiz) {
        env->DeleteGlobalRef(carEvsSvcFields.thiz);
        carEvsSvcFields.thiz = nullptr;
    }
}

/*
 * Forwards EVS stream events to the client
 */
void onStreamEvent(const EvsEventDesc& event) {
    JNIEnv* env = getOrAttachJNIEnvironment(carEvsSvcFields.vm, JNI_VERSION_1_6);
    if (!env) {
        LOG(ERROR) << __FUNCTION__ << ": Failed to get JNIEnvironment.";
        return;
    }

    // Gives an event callback
    env->CallVoidMethod(carEvsSvcFields.thiz, carEvsSvcFields.postNativeEventHandler,
                        static_cast<jint>(event.aType));
}

/*
 * Forwards EVS frames to the client
 */
void onNewFrame(const BufferDesc& frameDesc) {
    const auto createFromAHardwareBuffer =
            ::android::android_hardware_HardwareBuffer_createFromAHardwareBuffer;

    JNIEnv* env = getOrAttachJNIEnvironment(carEvsSvcFields.vm, JNI_VERSION_1_6);
    if (!env) {
        LOG(ERROR) << __FUNCTION__ << ": Failed to get JNIEnvironment.";
        return;
    }

    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle) {
        LOG(ERROR) << "Cannot forward a frame because EVS service handle is not valid.";
        return;
    }

    // Clones a AHardwareBuffer
    const AHardwareBuffer_Desc* desc =
            reinterpret_cast<const AHardwareBuffer_Desc*>(&frameDesc.buffer.description);

    AHardwareBuffer* rawBuffer;
    auto status = AHardwareBuffer_createFromHandle(desc, frameDesc.buffer.nativeHandle,
                                                   AHARDWAREBUFFER_CREATE_FROM_HANDLE_METHOD_CLONE,
                                                   &rawBuffer);
    if (status != android::NO_ERROR) {
        LOG(ERROR) << "Failed to create a raw hardware buffer from a native handle.";
        handle->doneWithFrame(frameDesc);
    } else {
        {
            std::lock_guard<std::mutex> lock(gRecordMutex);
            gBufferRecords.try_emplace(frameDesc.bufferId, frameDesc);
        }

        // Calls back
        jobject hwBuffer = createFromAHardwareBuffer(env, rawBuffer);
        env->CallVoidMethod(carEvsSvcFields.thiz, carEvsSvcFields.postNativeFrameHandler, hwBuffer);
        env->DeleteLocalRef(hwBuffer);
        AHardwareBuffer_release(rawBuffer);
    }
}

/*
 * Returns a consumed frame buffer to EVS service
 */
void returnFrameBuffer(JNIEnv* env, jobject /*thiz*/, jint bufferId) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isServiceAvailable()) {
        LOG(ERROR) << __FUNCTION__ << ": EVS service is not available.";
        return;
    }

    BufferDesc bufferToReturn;
    {
        std::lock_guard<std::mutex> lock(gRecordMutex);
        auto it = gBufferRecords.find(bufferId);
        if (it == gBufferRecords.end()) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                              "Unknown buffer is requested to return.");
            return;
        }

        bufferToReturn = it->second;
        gBufferRecords.erase(it);
    }
    handle->doneWithFrame(bufferToReturn);
}

/*
 * Open the target camera device for the service
 */
jboolean openCamera(JNIEnv* env, jobject /*thiz*/, jstring cameraId) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isServiceAvailable()) {
        LOG(ERROR) << __FUNCTION__ << ": EVS service is not available.";
        return JNI_FALSE;
    }

    // Attempts to open the target camera device
    const char* id = env->GetStringUTFChars(cameraId, JNI_FALSE);
    if (!handle->openCamera(id, onNewFrame, onStreamEvent)) {
        LOG(ERROR) << "Failed to open a camera device";
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(cameraId, JNI_FALSE);
    return JNI_TRUE;
}

/*
 * Close the target camera device
 */
void closeCamera(JNIEnv* env, jobject /*thiz*/) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isServiceAvailable()) {
        LOG(WARNING) << __FUNCTION__ << ": EVS service is not available.";
        return;
    }

    handle->closeCamera();
}

/*
 * Request to start a video stream
 */
jboolean startVideoStream(JNIEnv* env, jobject /*thiz*/) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isServiceAvailable()) {
        LOG(ERROR) << __FUNCTION__ << ": EVS service is not available.";
        return JNI_FALSE;
    }

    return handle->startVideoStream() ? JNI_TRUE : JNI_FALSE;
}

/*
 * Request to stop a video stream
 */
void stopVideoStream(JNIEnv* env, jobject /*thiz*/) {
    EvsServiceWrapper* handle = getServiceHandleOrThrowException(env);
    if (!handle || !handle->isServiceAvailable()) {
        LOG(WARNING) << __FUNCTION__ << ": EVS service is not available.";
        return;
    }

    handle->stopVideoStream();
}

}  // namespace

namespace android {

jint initializeCarEvsService(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG(ERROR) << __FUNCTION__ << ": Failed to get the environment.";
        return JNI_ERR;
    }

    // Registers native methods
    static const JNINativeMethod methods[] = {
            {"nativeConnectToHalServiceIfNecessary", "()Z",
                    reinterpret_cast<void*>(connectToHalServiceIfNecessary)},
            {"nativeOpenCamera", "(Ljava/lang/String;)Z",
                    reinterpret_cast<void*>(openCamera)},
            {"nativeCloseCamera", "()V",
                    reinterpret_cast<void*>(closeCamera)},
            {"nativeRequestToStartVideoStream", "()Z",
                    reinterpret_cast<void*>(startVideoStream)},
            {"nativeRequestToStopVideoStream", "()V",
                    reinterpret_cast<void*>(stopVideoStream)},
            {"nativeDoneWithFrame", "(I)V",
                    reinterpret_cast<void*>(returnFrameBuffer)},
            {"nativeDisconnectFromHalService", "()V",
                    reinterpret_cast<void*>(disconnectFromHalService)},
    };
    jniRegisterNativeMethods(env, kCarEvsServiceClassName, methods, NELEM(methods));

    // Gets the CarEvsService class
    jclass clazz = findClassOrDie(env, kCarEvsServiceClassName);

    // Initializes a service instance reference
    carEvsSvcFields.thiz = nullptr;

    // Gets mNativeEvsServiceObj variable field
    carEvsSvcFields.nativeEvsServiceObj = getFieldIdOrDie(env, clazz, "mNativeEvsServiceObj", "J");

    // Registers post-native handlers
    carEvsSvcFields.postNativeEventHandler =
            getMethodIDOrDie(env, clazz, "postNativeEventHandler", "(I)V");
    carEvsSvcFields.postNativeFrameHandler =
            getMethodIDOrDie(env, clazz, "postNativeFrameHandler",
                             "(ILandroid/hardware/HardwareBuffer;)V");
    carEvsSvcFields.postNativeDeathHandler =
            getMethodIDOrDie(env, clazz, "postNativeDeathHandler", "()V");
    env->DeleteLocalRef(clazz);

    // Caches JavaVM for future references
    carEvsSvcFields.vm = vm;

    return JNI_VERSION_1_6;
}

}  // namespace android
