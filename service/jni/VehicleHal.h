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

#ifndef CAR_VEHICLE_HAL_H_
#define CAR_VEHICLE_HAL_H_

#include <memory>

#include <hardware/hardware.h>
#include <hardware/vehicle.h>

#include <utils/threads.h>
#include <utils/List.h>

#include "HandlerThread.h"
#include "IVehicleHalEventListener.h"

namespace android {

/**
 * MessageHandler to dispatch HAL callbacks to pre-defined handler thread context.
 * Init / release is handled in the handler thread to allow upper layer to allocate resource
 * for the thread.
 */
class VehicleHalMessageHandler : public MessageHandler {
    enum {
        INIT = 0,
        RELEASE = 1,
        HAL_EVENT = 2,
        HAL_ERROR = 3,
    };

    /**
     * For dispatching HAL event in batch. Hal events coming in this time frame will be batched
     * together.
     */
    static const int DISPATCH_INTERVAL_MS = 16;
public:
    VehicleHalMessageHandler(const sp<Looper>& mLooper, IVehicleHalEventListener& listener);
    virtual ~VehicleHalMessageHandler();

    void handleInit();
    void handleRelease();
    void handleHalEvent(vehicle_prop_value_t *eventData);
    void handleHalError(int errorCode);

private:
    void handleMessage(const Message& message);
    void doHandleInit();
    void doHandleRelease();
    void doHandleHalEvent();
    void doHandleHalError();

private:
    Mutex mLock;
    Condition mHalThreadWait;
    sp<Looper> mLooper;
    IVehicleHalEventListener& mListener;
    int mLastError;
    int mFreeListIndex;
    List<vehicle_prop_value_t*> mHalPropertyList[2];
    int64_t mLastDispatchTime;
};

/**
 * C++ Wrapper for vehicle hal
 */
class VehicleHal {
public:
    VehicleHal(IVehicleHalEventListener& listener);
    ~VehicleHal();
    status_t init();
    void release();
    void onHalEvent(const vehicle_prop_value_t *eventData);
    void onHalError(int errorCode);
    vehicle_prop_config_t const * listProperties(int* numConfigs);
    status_t getProperty(vehicle_prop_value_t *data);
    status_t setProperty(vehicle_prop_value_t& data);
    status_t subscribe(int32_t prop, float sample_rate);
    void unsubscribe(int32_t prop);
private:
    status_t loadHal();
    void closeHal();
    static int eventCallback(const vehicle_prop_value_t *eventData);
    static int errorCallback(int32_t errorCode);
private:
    static VehicleHal* sInstance;
    HandlerThread mHandlerThread;
    sp<VehicleHalMessageHandler> mHandler;
    IVehicleHalEventListener& mListener;
    mutable Mutex mLock;
    vehicle_module_t* mModule;
    vehicle_hw_device_t* mDevice;
};

};

#endif /* CAR_VEHICLE_HAL_H_ */
