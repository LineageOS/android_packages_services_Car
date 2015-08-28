/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "CAR.HAL"

#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/SystemClock.h>
#include <jni.h>
#include <JNIHelp.h>

#include <memory>

#include "VehicleHal.h"
#include "IVehicleHalEventListener.h"

#define DBG

#ifdef DBG
#define DBG_LOG(x...) ALOGD(x)
#else
#define DBG_LOG(x...)
#endif

namespace android {


static const char* JAVA_HAL_PROPERTY_CLASS_NAME = "com/android/car/hal/HalProperty";

class JniVehicleHal : public IVehicleHalEventListener {
public:
    JniVehicleHal(jobject javaHal, jmethodID idOnHalDataEvents, int maxProperties, int maxData,
            jintArray properties, jlongArray timestamps,
            jintArray intData, jfloatArray floatData,
            JavaVM* jvm)
        : mJavaHal(javaHal),
          mIdOnHalDataEvents(idOnHalDataEvents),
          mNumMaxProperties(maxProperties),
          mNumMaxData(maxData),
          mProperties(new jint[4 * maxProperties]),
          mTimeStamps(new jlong[maxProperties]),
          mIntData(new jint[maxData]),
          mFloatData(new jfloat[maxData]),
          mJavaProperties(properties),
          mJavaTimestamps(timestamps),
          mJavaIntData(intData),
          mJavaFloatData(floatData),
          mHal(*this),
          mJvm(jvm)
            {
        // nothing to do
    };

    virtual ~JniVehicleHal() {
        //nothing to do
    };

    status_t init() {
        return mHal.init();
    };

    void release(JNIEnv* env) {
        // first stop hal thread.
        mHal.release();
        env->DeleteGlobalRef(mJavaProperties);
        env->DeleteGlobalRef(mJavaTimestamps);
        env->DeleteGlobalRef(mJavaIntData);
        env->DeleteGlobalRef(mJavaFloatData);
        env->DeleteGlobalRef(mJavaHal);
    };

    jobjectArray getSupportedProperties(JNIEnv *env) {
        int numProperties = -1;
        vehicle_prop_config_t const * list = mHal.listProperties(&numProperties);
        if (numProperties <= 0) {
            ALOGE("No properties from HAL, error:%d", numProperties);
            return NULL;
        }
        jclass halPropertyCls = env->FindClass(JAVA_HAL_PROPERTY_CLASS_NAME);
        if (halPropertyCls == NULL) {
            ALOGE("cannot load class %s", JAVA_HAL_PROPERTY_CLASS_NAME);
            return NULL;
        }
        jmethodID initMethidId =
                env->GetMethodID(halPropertyCls, "<init>", "(IIIIFF)V");
        if (initMethidId == NULL) {
            ALOGE("cannot find constructor for %s", JAVA_HAL_PROPERTY_CLASS_NAME);
            return NULL;
        }
        jobjectArray properties = env->NewObjectArray(numProperties, halPropertyCls, NULL);

        for (int i = 0; i < numProperties; i++) {
            //TODO add more members
            int propertyType = list->prop;
            int dataType = list->value_type;
            DBG_LOG("New property %x type %x", propertyType, dataType);
            jint accessType = list->access;
            jint changeMode = list->change_mode;
            jfloat minSampleRate = list->min_sample_rate;
            jfloat maxSampleRate = list->max_sample_rate;
            jobject prop = env->NewObject(halPropertyCls, initMethidId, propertyType, dataType,
                    accessType, changeMode, minSampleRate, maxSampleRate);
            env->SetObjectArrayElement(properties, i, prop);
            env->DeleteLocalRef(prop);
            list++;
        }
        return properties;
    };

    void dispatchCurrentEvents(int numProperties, int numPropertiesArray, int numIntValues,
            int numFloatValues) {
        mJniEnv->SetIntArrayRegion(mJavaProperties, 0, numPropertiesArray, mProperties.get());
        mJniEnv->SetLongArrayRegion(mJavaTimestamps, 0, numProperties, mTimeStamps.get());
        mJniEnv->SetIntArrayRegion(mJavaIntData, 0, numIntValues, mIntData.get());
        mJniEnv->SetFloatArrayRegion(mJavaFloatData, 0, numFloatValues, mFloatData.get());
        mJniEnv->CallVoidMethod(mJavaHal, mIdOnHalDataEvents, numProperties);
    }

    void onHalEvents(List<vehicle_prop_value_t*>& events) {
        DBG_LOG("onHalEvent, num events %d", events.size());
        int numProperties = 0;
        int indexPropertiesArray = 0;
        int indexCurrentIntValues = 0;
        int indexCurrentFloatValues = 0;
        for (auto& e : events) {
            numProperties++;
            int intSizeToAdd = 0;
            int floatSizeToAdd = 0;
            int type = 0;
            switch(e->value_type) {
            case VEHICLE_VALUE_TYPE_FLOAT:
                floatSizeToAdd = 1;
                break;
            case VEHICLE_VALUE_TYPE_INT64:
                intSizeToAdd = 2;
                break;
            case VEHICLE_VALUE_TYPE_INT32:
            case VEHICLE_VALUE_TYPE_BOOLEAN:
                intSizeToAdd = 1;
                break;
            default:
                ALOGE("onHalEvents type not implemented yet %d", e->value_type);
                break;
            /* TODO handle more types
            case VEHICLE_VALUE_TYPE_STRING:
            case HVAC: */
            }
            // One of arrays are full. Need to send upward now.
            if (((numProperties > mNumMaxProperties) ||
                    ((indexCurrentIntValues + intSizeToAdd) > mNumMaxData) ||
                    (indexCurrentFloatValues + floatSizeToAdd) > mNumMaxData)) {
                dispatchCurrentEvents(numProperties - 1, indexPropertiesArray,
                        indexCurrentIntValues,
                        indexCurrentFloatValues);
                numProperties = 1; // including the current one
                indexPropertiesArray = 0;
                indexCurrentIntValues = 0;
                indexCurrentFloatValues = 0;
            }
            // fill data now
            switch(e->value_type) {
            case VEHICLE_VALUE_TYPE_FLOAT:
                mFloatData[indexCurrentFloatValues] = e->value.float_value;
                indexCurrentFloatValues++;
                break;
            case VEHICLE_VALUE_TYPE_INT64:
                mIntData[indexCurrentIntValues] = (e->value.int64_value & 0xffffffff);
                indexCurrentIntValues++;
                mIntData[indexCurrentIntValues] = (e->value.int64_value >> 32);
                indexCurrentIntValues++;
                break;
            case VEHICLE_VALUE_TYPE_INT32:
            case VEHICLE_VALUE_TYPE_BOOLEAN:
                mIntData[indexCurrentIntValues] = e->value.int32_value;
                indexCurrentIntValues++;
                break;
            default:
                ALOGE("onHalEvents type not implemented yet %d", e->value_type);
                break;
                /* TODO handle more types
                        case VEHICLE_VALUE_TYPE_STRING:
                        case HVAC: */
            }
            mTimeStamps[numProperties - 1] = e->timestamp;
            mProperties[indexPropertiesArray] = e->prop;
            indexPropertiesArray++;
            mProperties[indexPropertiesArray] = e->value_type;
            indexPropertiesArray++;
            mProperties[indexPropertiesArray] = intSizeToAdd;
            indexPropertiesArray++;
            mProperties[indexPropertiesArray] = floatSizeToAdd;
            indexPropertiesArray++;
        }
        dispatchCurrentEvents(numProperties, indexPropertiesArray, indexCurrentIntValues,
                indexCurrentFloatValues);
    };

    void onHalError(int errorCode) {
        //TODO send error upward
    }

    void onHalThreadInit() {
        // called from HAL handler thread.
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = NULL;
        args.group = NULL;
        mJvm->AttachCurrentThread((JNIEnv**)&mJniEnv, &args);
    }

    void onHalThreadRelease() {
        mJvm->DetachCurrentThread();
    }

    inline void fillProperty(vehicle_prop_value_t* propValue, int property, int valueType) {
        propValue->prop = property;
        propValue->value_type = valueType;
    }

    status_t setIntProperty(int property, int value) {
        vehicle_prop_value_t propValue;
        fillProperty(&propValue, property, VEHICLE_VALUE_TYPE_INT32);
        propValue.value.int32_value = value;
        return mHal.setProperty(propValue);
    }

    status_t getIntProperty(int property, int32_t* value) {
        vehicle_prop_value_t propValue;
        propValue.prop = property;
        int r = mHal.getProperty(&propValue);
        if (r != NO_ERROR) {
            return r;
        }
        if (propValue.value_type != VEHICLE_VALUE_TYPE_INT32) {
            return BAD_TYPE;
        }
        *value = propValue.value.int32_value;
        return r;
    }

    status_t getLongProperty(int property, int64_t* value) {
        vehicle_prop_value_t propValue;
        propValue.prop = property;
        int r = mHal.getProperty(&propValue);
        if (r != NO_ERROR) {
            return r;
        }
        if (propValue.value_type != VEHICLE_VALUE_TYPE_INT64) {
            return BAD_TYPE;
        }
        *value = propValue.value.int64_value;
        return r;
    }

    status_t setFloatProperty(int property, float value) {
        vehicle_prop_value_t propValue;
        fillProperty(&propValue, property, VEHICLE_VALUE_TYPE_FLOAT);
        propValue.value.float_value = value;
        return mHal.setProperty(propValue);
    }

    status_t getFloatProperty(int property, float* value) {
        vehicle_prop_value_t propValue;
        propValue.prop = property;
        int r = mHal.getProperty(&propValue);
        if (r != NO_ERROR) {
            return r;
        }
        if (propValue.value_type != VEHICLE_VALUE_TYPE_FLOAT) {
            return BAD_TYPE;
        }
        *value = propValue.value.float_value;
        return r;
    }

    status_t getStringProperty(int property, uint8_t** data, int* length) {
        vehicle_prop_value_t propValue;
        propValue.prop = property;
        int r = mHal.getProperty(&propValue);
        if (r != NO_ERROR) {
            return r;
        }
        if (propValue.value_type != VEHICLE_VALUE_TYPE_STRING) {
            return BAD_TYPE;
        }
        *length = propValue.value.str_value.len;
        *data = propValue.value.str_value.data;
        return r;
    }

    status_t subscribeProperty(int property, float sampleRateHz) {
        return mHal.subscribe(property, sampleRateHz);
    }

    void unsubscribeProperty(int property) {
        mHal.unsubscribe(property);
    }

private:
    jobject mJavaHal;
    const jmethodID mIdOnHalDataEvents;
    const int mNumMaxProperties;
    const int mNumMaxData;
    std::unique_ptr<jint[]> mProperties;
    std::unique_ptr<jlong[]> mTimeStamps;
    std::unique_ptr<jint[]> mIntData;
    std::unique_ptr<jfloat[]> mFloatData;
    jintArray mJavaProperties;
    jlongArray mJavaTimestamps;
    jintArray mJavaIntData;
    jfloatArray mJavaFloatData;
    VehicleHal mHal;
    JavaVM* mJvm;
    JNIEnv* mJniEnv;
};

static const char* JAVA_ILLEGAL_STATE_EXCEPTION_CLASS_NAME = "java/lang/IllegalStateException";
static const char* JAVA_RUNTIME_EXCEPTION_CLASS_NAME = "java/lang/RuntimeException";

static void throwException(JNIEnv *env, const char* exceptionClass, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    String8 msg(String8::formatV(fmt, args));
    ALOGE("%s", msg.string());
    env->ThrowNew(env->FindClass(exceptionClass), msg.string());
}

static jmethodID getMethodID(JNIEnv *env, jclass clz, const char* name, const char* sig) {
    const jmethodID r = env->GetMethodID(clz, name, sig);
    if (r == 0) {
        throwException(env, JAVA_RUNTIME_EXCEPTION_CLASS_NAME,
                "cannot find method %s with signature %s from Java Hal", name);
    }
    return r;
}

static void assertGetError(JNIEnv *env, int errorCode) {
    if (errorCode != NO_ERROR) {
        throwException(env, JAVA_ILLEGAL_STATE_EXCEPTION_CLASS_NAME,
                        "cannot get property, returned %d", errorCode);
    }
}

static void assertSetError(JNIEnv *env, int errorCode) {
    if (errorCode != NO_ERROR) {
        throwException(env, JAVA_ILLEGAL_STATE_EXCEPTION_CLASS_NAME,
                        "cannot set property, returned %d", errorCode);
    }
}

static jlong com_android_car_hal_VehicleHal_nativeInit(JNIEnv *env, jobject javaHal,
        jintArray properties, jlongArray timestamps, jintArray intData, jfloatArray floatData) {
    const int maxProperties = env->GetArrayLength(properties);
    const int maxData = env->GetArrayLength(intData);
    jclass javaHalCls = env->GetObjectClass(javaHal);
    const jmethodID idOnHalDataEvents = getMethodID(env, javaHalCls, "onHalDataEvents",
            "(I)V");
    if (idOnHalDataEvents == 0) {
        return 0;
    }
    jobject globalJavaHal = env->NewGlobalRef(javaHal);
    jintArray globalProperties = (jintArray) env->NewGlobalRef(properties);
    jlongArray globalTimestamps = (jlongArray) env->NewGlobalRef(timestamps);
    jintArray globalIntData = (jintArray) env->NewGlobalRef(intData);
    jfloatArray globalFloatData = (jfloatArray) env->NewGlobalRef(floatData);
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    JniVehicleHal* hal = new JniVehicleHal(
            globalJavaHal,
            idOnHalDataEvents,
            maxProperties,
            maxData,
            globalProperties,
            globalTimestamps,
            globalIntData,
            globalFloatData,
            jvm);
    status_t r = hal->init();
    if (r != NO_ERROR) {
        throwException(env, JAVA_RUNTIME_EXCEPTION_CLASS_NAME, "cannot init hal, returned %d", r);
    }
    return (jlong) hal;
}

static void com_android_car_hal_VehicleHal_nativeRelease(JNIEnv *env, jobject, jlong jniHal) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    // Little ugly here as all global ref should be released inside release.
    hal->release(env);
    delete hal;
}

static jobjectArray com_android_car_hal_VehicleHal_getSupportedProperties(JNIEnv *env, jobject,
        jlong jniHal) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    return hal->getSupportedProperties(env);
}

static void com_android_car_hal_VehicleHal_setIntProperty(JNIEnv *env, jobject, jlong jniHal,
        jint property, jint value) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    status_t r = hal->setIntProperty(property, value);
    assertSetError(env, r);
}

static jlong com_android_car_hal_VehicleHal_getLongProperty(JNIEnv *env, jobject, jlong jniHal,
        jint property) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    jlong value = -1;
    status_t r = hal->getLongProperty(property, &value);
    assertGetError(env, r);
    return value;
}

static jint com_android_car_hal_VehicleHal_getIntProperty(JNIEnv *env, jobject, jlong jniHal,
        jint property) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    int value = -1;
    status_t r = hal->getIntProperty(property, &value);
    assertGetError(env, r);
    return value;
}

static void com_android_car_hal_VehicleHal_setFloatProperty(JNIEnv *env, jobject, jlong jniHal,
        jint property, jfloat value) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    status_t r = hal->setFloatProperty(property, value);
    assertSetError(env, r);
}

static jfloat com_android_car_hal_VehicleHal_getFloatProperty(JNIEnv *env, jobject,
        jlong jniHal, jint property) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    float value = -1;
    status_t r = hal->getFloatProperty(property, &value);
    assertGetError(env, r);
    return value;
}

static jbyteArray com_android_car_hal_VehicleHal_getStringProperty(JNIEnv *env, jobject,
        jlong jniHal, jint property) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    uint8_t* data = NULL;
    int len = 0;
    status_t r = hal->getStringProperty(property, &data, &len);
    assertGetError(env, r);
    if (r == NO_ERROR && len > 0 && data != NULL) {
        jbyteArray array = env->NewByteArray(len);
        env->SetByteArrayRegion(array, 0, len, (jbyte*)data);
        delete[] data;
        return array;
    }
    return NULL;
}

static int com_android_car_hal_VehicleHal_subscribeProperty(JNIEnv *, jobject, jlong jniHal,
        jint property, jfloat sampleRateHz) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    return hal->subscribeProperty(property, sampleRateHz);
}

static void com_android_car_hal_VehicleHal_unsubscribeProperty(JNIEnv *, jobject, jlong jniHal,
        jint property) {
    JniVehicleHal* hal = reinterpret_cast<JniVehicleHal*>(jniHal);
    return hal->unsubscribeProperty(property);
}

static JNINativeMethod gMethods[] = {
    { "nativeInit", "([I[J[I[F)J", (void*)com_android_car_hal_VehicleHal_nativeInit },
    { "nativeRelease", "(J)V", (void*)com_android_car_hal_VehicleHal_nativeRelease },
    { "getSupportedProperties", "(J)[Lcom/android/car/hal/HalProperty;", (void*)com_android_car_hal_VehicleHal_getSupportedProperties },
    { "setIntProperty", "(JII)V", (void*)com_android_car_hal_VehicleHal_setIntProperty },
    { "getIntProperty", "(JI)I", (void*)com_android_car_hal_VehicleHal_getIntProperty },
    { "getLongProperty", "(JI)J", (void*)com_android_car_hal_VehicleHal_getLongProperty },
    { "setFloatProperty", "(JIF)V", (void*)com_android_car_hal_VehicleHal_setFloatProperty },
    { "getFloatProperty", "(JI)F", (void*)com_android_car_hal_VehicleHal_getFloatProperty },
    { "getStringProperty", "(JI)[B", (void*)com_android_car_hal_VehicleHal_getStringProperty },
    { "subscribeProperty", "(JIF)I", (void*)com_android_car_hal_VehicleHal_subscribeProperty },
    { "unsubscribeProperty", "(JI)V", (void*)com_android_car_hal_VehicleHal_unsubscribeProperty },
};

int register_com_android_car_hal_VehicleHal(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/car/hal/VehicleHal",
            gMethods, NELEM(gMethods));
}
};
