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

#ifndef CAR_VEHICLE_NETWORK_SERVICE_H_
#define CAR_VEHICLE_NETWORK_SERVICE_H_

#include <stdint.h>
#include <sys/types.h>

#include <memory>

#include <hardware/hardware.h>
#include <hardware/vehicle.h>

#include <binder/BinderService.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <cutils/compiler.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/StrongPointer.h>
#include <utils/TypeHelpers.h>

#include <IVehicleNetwork.h>
#include <IVehicleNetworkListener.h>

#include "HandlerThread.h"


namespace android {

class VehicleNetworkService;

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
    VehicleHalMessageHandler(const sp<Looper>& mLooper, VehicleNetworkService& service);
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
    VehicleNetworkService& mService;
    int mLastError;
    int mFreeListIndex;
    List<vehicle_prop_value_t*> mHalPropertyList[2];
    int64_t mLastDispatchTime;
};

// ----------------------------------------------------------------------------

class HalClient : public virtual RefBase {
public:
    HalClient(const sp<IVehicleNetworkListener> &listener) :
        mListener(listener) {
        IPCThreadState* self = IPCThreadState::self();
        mPid = self->getCallingPid();
        mUid = self->getCallingUid();
    }

    ~HalClient() {
        mSampleRates.clear();
    }

    pid_t getPid() {
        return mPid;
    }

    uid_t getUid() {
        return mUid;
    }

    float getSampleRate(int32_t property){
        Mutex::Autolock autoLock(mLock);
        ssize_t index = mSampleRates.indexOfKey(property);
        if (index < 0) {
            return -1;
        }
        return mSampleRates.valueAt(index);
    }

    void setSampleRate(int32_t property, float sampleRate){
        Mutex::Autolock autoLock(mLock);
        mSampleRates.add(property, sampleRate);
    }

    bool removePropertyAndCheckIfActive(int32_t property) {
        mSampleRates.removeItem(property);
        return mSampleRates.size() > 0;
    }

    const sp<IVehicleNetworkListener>& getListener() {
        return mListener;
    }

    const sp<IBinder> getListenerAsBinder() {
        return IInterface::asBinder(mListener);
    }

    // no lock here as this should be called only from single event looper thread
    void addEvent(vehicle_prop_value_t* event) {
        mEvents.push_back(event);
    }

    // no lock here as this should be called only from single event looper thread
    void clearEvents() {
        mEvents.clear();
    }

    // no lock here as this should be called only from single event looper thread
    List<vehicle_prop_value_t*>& getEventList() {
        return mEvents;
    }

    // no lock here as this should be called only from single event looper thread
    status_t dispatchEvents(){
        ALOGV("dispatchEvents, num Events:%d", mEvents.size());
        sp<VehiclePropValueListHolder> events(new VehiclePropValueListHolder(&mEvents,
                false /*deleteInDestructor */));
        ASSERT_OR_HANDLE_NO_MEMORY(events.get(), return NO_MEMORY);
        mListener->onEvents(events);
        mEvents.clear();
        return NO_ERROR;
    }
private:
    pid_t mPid;
    uid_t mUid;
    Mutex mLock;
    sp<IVehicleNetworkListener> mListener;
    KeyedVector<int32_t, float> mSampleRates;
    List<vehicle_prop_value_t*> mEvents;
};

class HalClientSpVector : public SortedVector<sp<HalClient> >, public RefBase {
protected:
    virtual int do_compare(const void* lhs, const void* rhs) const {
        sp<HalClient>& lh = * (sp<HalClient> * )(lhs);
        sp<HalClient>& rh = * (sp<HalClient> * )(rhs);
        return compare_type(lh.get(), rh.get());
    }
};

// ----------------------------------------------------------------------------

class VehicleNetworkService :
    public BinderService<VehicleNetworkService>,
    public BnVehicleNetwork,
    public IBinder::DeathRecipient {
public:
    static const char* getServiceName() ANDROID_API { return IVehicleNetwork::SERVICE_NAME; };

    VehicleNetworkService();
    ~VehicleNetworkService();
    virtual status_t dump(int fd, const Vector<String16>& args);
    void release();
    void onHalEvent(const vehicle_prop_value_t *eventData);
    void onHalError(int errorCode);
    /**
     * Called by VehicleHalMessageHandler for batching events
     */
    void onHalEvents(List<vehicle_prop_value_t*>& events);
    virtual sp<VehiclePropertiesHolder> listProperties(int32_t property = 0);
    virtual status_t setProperty(const vehicle_prop_value_t& value);
    virtual status_t getProperty(vehicle_prop_value_t* value);
    virtual status_t subscribe(const sp<IVehicleNetworkListener> &listener, int32_t property,
            float sampleRate);
    virtual void unsubscribe(const sp<IVehicleNetworkListener> &listener, int32_t property);
    virtual void binderDied(const wp<IBinder>& who);
private:
    // RefBase
    virtual void onFirstRef();
    status_t loadHal();
    void closeHal();
    vehicle_prop_config_t const * findConfig(int32_t property);
    bool isGettable(int32_t property);
    bool isSettable(int32_t property);
    bool isSubscribable(int32_t property);
    static int eventCallback(const vehicle_prop_value_t *eventData);
    static int errorCallback(int32_t errorCode);
private:
    static VehicleNetworkService* sInstance;
    HandlerThread mHandlerThread;
    sp<VehicleHalMessageHandler> mHandler;
    mutable Mutex mLock;
    vehicle_module_t* mModule;
    vehicle_hw_device_t* mDevice;
    sp<VehiclePropertiesHolder> mProperties;
    KeyedVector<sp<IBinder>, sp<HalClient> > mBinderToClientMap;
    KeyedVector<int32_t, sp<HalClientSpVector> > mPropertyToClientsMap;
    KeyedVector<int32_t, float> mSampleRates;
};

};

#endif /* CAR_VEHICLE_NETWORK_SERVICE_H_ */
