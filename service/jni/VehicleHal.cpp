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

#include <utils/Errors.h>
#include <utils/SystemClock.h>

#include "VehicleHal.h"

namespace android {

VehicleHalMessageHandler::VehicleHalMessageHandler(const sp<Looper>& looper,
        IVehicleHalEventListener& listener)
    : mLooper(looper),
      mListener(listener),
      mFreeListIndex(0),
      mLastDispatchTime(0) {
}

VehicleHalMessageHandler::~VehicleHalMessageHandler() {

}

void VehicleHalMessageHandler::handleInit() {
    Mutex::Autolock autoLock(mLock);
    mLooper->sendMessage(this, Message(INIT));
}

void VehicleHalMessageHandler::handleRelease() {
    Mutex::Autolock autoLock(mLock);
    mLooper->sendMessage(this, Message(RELEASE));
    mHalThreadWait.wait(mLock);
}

static const int MS_TO_NS = 1000000;

void VehicleHalMessageHandler::handleHalEvent(vehicle_prop_value_t *eventData) {
    Mutex::Autolock autoLock(mLock);
    List<vehicle_prop_value_t*>& propList = mHalPropertyList[mFreeListIndex];
    propList.push_back(eventData);
    int64_t deltaFromLast = elapsedRealtime() - mLastDispatchTime;
    if (deltaFromLast > DISPATCH_INTERVAL_MS) {
        mLooper->sendMessage(this, Message(HAL_EVENT));
    } else {
        mLooper->sendMessageDelayed((DISPATCH_INTERVAL_MS - deltaFromLast) * MS_TO_NS,
                this, Message(HAL_EVENT));
    }
}

void VehicleHalMessageHandler::handleHalError(int errorCode) {
    Mutex::Autolock autoLock(mLock);
    // Do not care about overwriting previous error as any error is critical anyway.
    mLastError = errorCode;
    mLooper->sendMessage(this, Message(HAL_ERROR));
}

void VehicleHalMessageHandler::doHandleInit() {
    mListener.onHalThreadInit();
}

void VehicleHalMessageHandler::doHandleRelease() {
    mListener.onHalThreadRelease();
    Mutex::Autolock autoLock(mLock);
    mHalThreadWait.broadcast();
}

void VehicleHalMessageHandler::doHandleHalEvent() {
    // event dispatching can take time, so do it outside lock and that requires double buffering.
    // inside lock, free buffer is swapped with non-free buffer.
    List<vehicle_prop_value_t*>* events = NULL;
    do {
        Mutex::Autolock autoLock(mLock);
        mLastDispatchTime = elapsedRealtime();
        int nonFreeListIndex = mFreeListIndex ^ 0x1;
        List<vehicle_prop_value_t*>* nonFreeList = &(mHalPropertyList[nonFreeListIndex]);
        List<vehicle_prop_value_t*>* freeList = &(mHalPropertyList[mFreeListIndex]);
        if (nonFreeList->size() > 0) {
            for (auto& e : *freeList) {
                nonFreeList->push_back(e);
            }
            freeList->clear();
            events = nonFreeList;
        } else if (freeList->size() > 0) {
            events = freeList;
            mFreeListIndex = nonFreeListIndex;
        }
    } while (false);
    if (events != NULL) {
        mListener.onHalEvents(*events);
        //TODO implement return to memory pool
        for (auto& e : *events) {
            delete e;
            //TODO delete pointer type properly.
        }
        events->clear();
    }
}

void VehicleHalMessageHandler::doHandleHalError() {
    Mutex::Autolock autoLock(mLock);
    mListener.onHalError(mLastError);
}

void VehicleHalMessageHandler::handleMessage(const Message& message) {
    switch (message.what) {
    case INIT:
        doHandleInit();
        break;
    case RELEASE:
        doHandleRelease();
        break;
    case HAL_EVENT:
        doHandleHalEvent();
        break;
    case HAL_ERROR:
        doHandleHalError();
        break;
    }
}

// store HAL instance for callback
VehicleHal* VehicleHal::sInstance = NULL;

VehicleHal::VehicleHal(IVehicleHalEventListener& listener)
    : mListener(listener),
      mModule(NULL) {
    //TODO
    sInstance = this;
}

VehicleHal::~VehicleHal() {
    //TODO
    sInstance = NULL;
}

int VehicleHal::eventCallback(const vehicle_prop_value_t *eventData) {
    sInstance->onHalEvent(eventData);
    return NO_ERROR;
}


int VehicleHal::errorCallback(uint32_t errorCode) {
    sInstance->onHalError(errorCode);
    return NO_ERROR;
}

status_t VehicleHal::init() {
    Mutex::Autolock autoLock(mLock);
    status_t r = loadHal();
    if (r!= NO_ERROR) {
        ALOGE("cannot load HAL, error:%d", r);
        return r;
    }
    r = mHandlerThread.start("HAL.NATIVE_LOOP");
    if (r != NO_ERROR) {
        ALOGE("cannot start handler thread, error:%d", r);
        return r;
    }
    sp<VehicleHalMessageHandler> handler(new VehicleHalMessageHandler(mHandlerThread.getLooper(),
            mListener));
    mHandler = handler;
    mHandler->handleInit();
    r = mDevice->init(mDevice, eventCallback, errorCallback);
    if (r != NO_ERROR) {
        ALOGE("HAL init failed:%d", r);
        return r;
    }
    return NO_ERROR;
}

void VehicleHal::release() {
    Mutex::Autolock autoLock(mLock);
    mDevice->release(mDevice);
    if (mHandler.get() != NULL) {
        mHandler->handleRelease();
    }
    mHandlerThread.quit();
}

vehicle_prop_config_t const * VehicleHal::listProperties(int* numConfigs) {
    return mDevice->list_properties(mDevice, numConfigs);
}

status_t VehicleHal::getProperty(vehicle_prop_value_t *data) {
    return mDevice->get(mDevice, data);
}

status_t VehicleHal::setProperty(vehicle_prop_value_t& data) {
    return mDevice->set(mDevice, &data);
}

status_t VehicleHal::subscribe(uint32_t prop, float sampleRate) {
    return mDevice->subscribe(mDevice, prop, sampleRate);
}

void VehicleHal::unsubscribe(uint32_t prop) {
    mDevice->unsubscribe(mDevice, prop);
}

void VehicleHal::onHalEvent(const vehicle_prop_value_t *eventData) {
    //TODO add memory pool
    //TODO handle pointer type data which requires one more alloc / copy
    vehicle_prop_value_t* copy = new vehicle_prop_value_t();
    memcpy(copy, eventData, sizeof(vehicle_prop_value_t));
    mHandler->handleHalEvent(copy);
}

void VehicleHal::onHalError(int errorCode) {
    mHandler->handleHalError(errorCode);
}

status_t VehicleHal::loadHal() {
    int r = hw_get_module(VEHICLE_HARDWARE_MODULE_ID, (hw_module_t const**)&mModule);
    if (r != NO_ERROR) {
        ALOGE("cannot load HAL module, error:%d", r);
        return r;
    }
    r = mModule->common.methods->open(&mModule->common, VEHICLE_HARDWARE_DEVICE,
            (hw_device_t**)&mDevice);
    return r;
}

void VehicleHal::closeHal() {
    mDevice->common.close(&mDevice->common);
}
};
