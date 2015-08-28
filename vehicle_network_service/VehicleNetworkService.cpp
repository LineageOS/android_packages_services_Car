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
#define LOG_TAG "VehicleNetwork"

#include <binder/PermissionCache.h>
#include <utils/Errors.h>
#include <utils/SystemClock.h>

#include "VehicleNetworkService.h"

//#define DBG_EVENT
#ifdef DBG_EVENT
#define EVENT_LOG(x...) ALOGD(x)
#else
#define EVENT_LOG(x...)
#endif

namespace android {

VehicleHalMessageHandler::VehicleHalMessageHandler(const sp<Looper>& looper,
        VehicleNetworkService& service)
    : mLooper(looper),
      mService(service),
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
    EVENT_LOG("handleHalEvent 0x%x", eventData->prop);
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
    // nothing to do
}

void VehicleHalMessageHandler::doHandleRelease() {
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
        EVENT_LOG("doHandleHalEvent, num events:%d", events->size());
        mService.onHalEvents(*events);
        //TODO implement return to memory pool
        for (auto& e : *events) {
            VehiclePropValueUtil::deleteMembers(e);
            delete e;
        }
        events->clear();
    }
}

void VehicleHalMessageHandler::doHandleHalError() {
    Mutex::Autolock autoLock(mLock);
    //TODO remove?
    //mService.onHalError(mLastError);
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

// ----------------------------------------------------------------------------


VehicleNetworkService* VehicleNetworkService::sInstance = NULL;

status_t VehicleNetworkService::dump(int fd, const Vector<String16>& /*args*/) {
    static const String16 sDump("android.permission.DUMP");
    String8 msg;
    if (!PermissionCache::checkCallingPermission(sDump)) {
        msg.appendFormat("Permission Denial: "
                    "can't dump VNS from pid=%d, uid=%d\n",
                    IPCThreadState::self()->getCallingPid(),
                    IPCThreadState::self()->getCallingUid());
        write(fd, msg.string(), msg.size());
        return NO_ERROR;
    }

    msg.append("*Active clients*\n");
    for (size_t i = 0; i < mBinderToClientMap.size(); i++) {
        msg.appendFormat("pid %d uid %d\n", mBinderToClientMap.valueAt(i)->getPid(),
                mBinderToClientMap.valueAt(i)->getUid());
    }
    msg.append("*Active clients per property*\n");
    for (size_t i = 0; i < mPropertyToClientsMap.size(); i++) {
        msg.appendFormat("prop 0x%x, pids:", mPropertyToClientsMap.keyAt(i));
        sp<HalClientSpVector> clients = mPropertyToClientsMap.valueAt(i);
        for (size_t j = 0; j < clients->size(); j++) {
            msg.appendFormat("%d,", clients->itemAt(j)->getPid());
        }
        msg.append("\n");
    }
    msg.append("*Sample rates per property*\n");
    for (size_t i = 0; i < mSampleRates.size(); i++) {
        msg.appendFormat("prop 0x%x, sample rate %f Hz\n", mSampleRates.keyAt(i),
                mSampleRates.valueAt(i));
    }
    write(fd, msg.string(), msg.size());
    return NO_ERROR;
}

VehicleNetworkService::VehicleNetworkService()
    : mModule(NULL) {
    //TODO
    sInstance = this;
}

VehicleNetworkService::~VehicleNetworkService() {
    sInstance = NULL;
    for (size_t i = 0; i < mPropertyToClientsMap.size(); i++) {
        sp<HalClientSpVector> clients = mPropertyToClientsMap.editValueAt(i);
        clients->clear();
    }
    mBinderToClientMap.clear();
    mPropertyToClientsMap.clear();
    mSampleRates.clear();
}

void VehicleNetworkService::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock autoLock(mLock);
    sp<IBinder> iBinder = who.promote();

    ssize_t index = mBinderToClientMap.indexOfKey(iBinder);
    if (index < 0) {
        // already removed. ignore
        return;
    }
    sp<HalClient> currentClient = mBinderToClientMap.editValueAt(index);
    mBinderToClientMap.removeItemsAt(index);
    for (size_t i = 0; i < mPropertyToClientsMap.size(); i++) {
        sp<HalClientSpVector>& clients = mPropertyToClientsMap.editValueAt(i);
        clients->remove(currentClient);
        // TODO update frame rate
        if (clients->size() == 0) {
            int32_t prop = mPropertyToClientsMap.keyAt(i);
            mDevice->unsubscribe(mDevice, prop);
            mPropertyToClientsMap.removeItemsAt(i);
            mSampleRates.removeItem(prop);
        }
    }
}

int VehicleNetworkService::eventCallback(const vehicle_prop_value_t *eventData) {
    EVENT_LOG("eventCallback 0x%x");
    sInstance->onHalEvent(eventData);
    return NO_ERROR;
}


int VehicleNetworkService::errorCallback(int32_t errorCode) {
    sInstance->onHalError(errorCode);
    return NO_ERROR;
}

void VehicleNetworkService::onFirstRef() {
    Mutex::Autolock autoLock(mLock);
    status_t r = loadHal();
    if (r!= NO_ERROR) {
        ALOGE("cannot load HAL, error:%d", r);
        return;
    }
    r = mHandlerThread.start("HAL.NATIVE_LOOP");
    if (r != NO_ERROR) {
        ALOGE("cannot start handler thread, error:%d", r);
        return;
    }
    sp<VehicleHalMessageHandler> handler(new VehicleHalMessageHandler(mHandlerThread.getLooper(),
            *this));
    ASSERT_ALWAYS_ON_NO_MEMORY(handler.get());
    mHandler = handler;
    mHandler->handleInit();
    r = mDevice->init(mDevice, eventCallback, errorCallback);
    if (r != NO_ERROR) {
        ALOGE("HAL init failed:%d", r);
        return;
    }
    int numConfigs = 0;
    vehicle_prop_config_t const* configs = mDevice->list_properties(mDevice, &numConfigs);
    mProperties = new VehiclePropertiesHolder(const_cast<vehicle_prop_config_t*>(configs),
            numConfigs, false /* deleteConfigsInDestructor */);
    ASSERT_ALWAYS_ON_NO_MEMORY(mProperties);
}

void VehicleNetworkService::release() {
    Mutex::Autolock autoLock(mLock);
    mDevice->release(mDevice);
    if (mHandler.get() != NULL) {
        mHandler->handleRelease();
    }
    mHandlerThread.quit();
}

vehicle_prop_config_t const * VehicleNetworkService::findConfig(int32_t property) {
    vehicle_prop_config_t const * config = mProperties->getData();
    int32_t numConfigs = mProperties->getNumConfigs();
    for (int32_t i = 0; i < numConfigs; i++) {
        if (config->prop == property) {
            return config;
        }
        config++;
    }
    return NULL;
}


bool VehicleNetworkService::isGettable(int32_t property) {
    vehicle_prop_config_t const * config = findConfig(property);
    if (config == NULL) {
        return false;
    }
    if ((config->access & VEHICLE_PROP_ACCESS_READ) == 0) {
        ALOGE("cannot get, property 0x%x is write only", property);
        return false;
    }
    return true;
}

bool VehicleNetworkService::isSettable(int32_t property) {
    vehicle_prop_config_t const * config = findConfig(property);
    if (config == NULL) {
        return false;
    }
    if ((config->access & VEHICLE_PROP_ACCESS_WRITE) == 0) {
        ALOGE("cannot set, property 0x%x is read only", property);
        return false;
    }
    return true;
}

bool VehicleNetworkService::isSubscribable(int32_t property) {
    vehicle_prop_config_t const * config = findConfig(property);
    if (config == NULL) {
        return false;
    }
    if ((config->access & VEHICLE_PROP_ACCESS_READ) == 0) {
        ALOGE("cannot subscribe, property 0x%x is write only", property);
        return false;
    }
    if (config->change_mode == VEHICLE_PROP_CHANGE_MODE_STATIC) {
        ALOGE("cannot subscribe, property 0x%x is static", property);
        return false;
    }
    return true;
}

sp<VehiclePropertiesHolder> VehicleNetworkService::listProperties(int32_t property) {
    Mutex::Autolock autoLock(mLock);
    if (property == 0) {
        return mProperties;
    } else {
        sp<VehiclePropertiesHolder> p;
        vehicle_prop_config_t const * config = findConfig(property);
        if (config != NULL) {
            p = new VehiclePropertiesHolder(const_cast<vehicle_prop_config_t*>(config), 1,
                    false /* deleteConfigsInDestructor */);
            ASSERT_OR_HANDLE_NO_MEMORY(p.get(), return p);
        }
        return p;
    }
}

status_t VehicleNetworkService::getProperty(vehicle_prop_value_t *data) {
    do { // for lock scoping
        Mutex::Autolock autoLock(mLock);
        if (!isGettable(data->prop)) {
            return BAD_VALUE;
        }
        //TODO caching for static, on-change type?
    } while (false);
    // set done outside lock to allow concurrent access
    return mDevice->get(mDevice, data);
}

status_t VehicleNetworkService::setProperty(const vehicle_prop_value_t& data) {
    do { // for lock scoping
        Mutex::Autolock autoLock(mLock);
        if (!isSettable(data.prop)) {
            return BAD_VALUE;
        }
    } while (false);
    //TODO add value check requires auto generated code to return value range for enum types
    // set done outside lock to allow concurrent access
    return mDevice->set(mDevice, &data);
}

status_t VehicleNetworkService::subscribe(const sp<IVehicleNetworkListener> &listener, int32_t prop,
        float sampleRate) {
    Mutex::Autolock autoLock(mLock);
    if (!isSubscribable(prop)) {
        return BAD_VALUE;
    }
    vehicle_prop_config_t const * config = findConfig(prop);
    if (config->change_mode == VEHICLE_PROP_CHANGE_MODE_ON_CHANGE) {
        if (sampleRate != 0) {
            ALOGW("Sample rate set to non-zeo for on change type. Ignore it");
            sampleRate = 0;
        }
    } else {
        if (sampleRate > config->max_sample_rate) {
            ALOGW("sample rate %f higher than max %f. limit to max", sampleRate,
                    config->max_sample_rate);
            sampleRate = config->max_sample_rate;
        }
        if (sampleRate < config->min_sample_rate) {
            ALOGW("sample rate %f lower than min %f. limit to min", sampleRate,
                                config->min_sample_rate);
            sampleRate = config->min_sample_rate;
        }
    }
    sp<IBinder> iBinder = IInterface::asBinder(listener);
    ALOGD("subscribe, binder 0x%x prop 0x%x", iBinder.get(), prop);
    sp<HalClient> client;
    sp<HalClientSpVector> clientsForProperty;
    bool createClient = false;
    ssize_t index = mBinderToClientMap.indexOfKey(iBinder);
    if (index < 0) {
        createClient = true;
    } else {
        client = mBinderToClientMap.editValueAt(index);
        index = mPropertyToClientsMap.indexOfKey(prop);
        if (index >= 0) {
            clientsForProperty = mPropertyToClientsMap.editValueAt(index);
        }
    }
    if (clientsForProperty.get() == NULL) {
        clientsForProperty = new HalClientSpVector();
        ASSERT_OR_HANDLE_NO_MEMORY(clientsForProperty.get(), return NO_MEMORY);
        mPropertyToClientsMap.add(prop, clientsForProperty);
    }
    if (createClient) {
        client = new HalClient(listener);
        ASSERT_OR_HANDLE_NO_MEMORY(client.get(), return NO_MEMORY);
        iBinder->linkToDeath(this);
        ALOGV("add binder 0x%x to map", iBinder.get());
        mBinderToClientMap.add(iBinder, client);
    }
    clientsForProperty->add(client);

    index = mSampleRates.indexOfKey(prop);
    bool shouldSubscribe = false;
    if (index < 0) {
        // first time subscription for this property
        shouldSubscribe = true;
    } else {
        float currentSampleRate = mSampleRates.valueAt(index);
        if (currentSampleRate < sampleRate) {
            shouldSubscribe = true;
        }
    }
    client->setSampleRate(prop, sampleRate);
    if (shouldSubscribe) {
        mSampleRates.add(prop, sampleRate);
        ALOGD("subscribe to HAL, prop 0x%x sample rate:%f", prop, sampleRate);
        return mDevice->subscribe(mDevice, prop, sampleRate);
    } else {
        return NO_ERROR;
    }
}

void VehicleNetworkService::unsubscribe(const sp<IVehicleNetworkListener> &listener, int32_t prop) {
    Mutex::Autolock autoLock(mLock);
    if (!isSubscribable(prop)) {
        return;
    }
    sp<IBinder> iBinder = IInterface::asBinder(listener);
    ALOGD("unsubscribe, binder 0x%x, prop 0x%x", iBinder.get(), prop);
    ssize_t index = mBinderToClientMap.indexOfKey(iBinder);
    if (index < 0) {
        // client not found
        ALOGD("unsubscribe client not found in binder map");
        return;
    }
    sp<HalClient>& client = mBinderToClientMap.editValueAt(index);
    index = mPropertyToClientsMap.indexOfKey(prop);
    if (index < 0) {
        // not found
        ALOGD("unsubscribe client not found in prop map, prop:0x%x", prop);
        return;
    }
    sp<HalClientSpVector> clientsForProperty = mPropertyToClientsMap.editValueAt(index);
    //TODO share code with binderDied
    clientsForProperty->remove(client);
    if(!client->removePropertyAndCheckIfActive(prop)) {
        // client is no longer necessary
        mBinderToClientMap.removeItem(iBinder);
        iBinder->unlinkToDeath(this);
    }
    //TODO reset sample rate. do not care for now.
    if (clientsForProperty->size() == 0) {
        mDevice->unsubscribe(mDevice, prop);
        mPropertyToClientsMap.removeItem(prop);
        mSampleRates.removeItem(prop);
    }
}

void VehicleNetworkService::onHalEvent(const vehicle_prop_value_t* eventData) {
    //TODO add memory pool
    vehicle_prop_value_t* copy = VehiclePropValueUtil::copyVehicleProp(*eventData);
    mHandler->handleHalEvent(copy);
}

void VehicleNetworkService::onHalError(int errorCode) {
    //TODO call listener directly?
    //mHandler->handleHalError(errorCode);
}

void VehicleNetworkService::onHalEvents(List<vehicle_prop_value_t*>& events) {
    HalClientSpVector activeClients;
    do { // for lock scoping
        Mutex::Autolock autoLock(mLock);
        for (vehicle_prop_value_t* e : events) {
            ssize_t index = mPropertyToClientsMap.indexOfKey(e->prop);
            if (index < 0) {
                ALOGE("HAL event for not subscribed property 0x%x", e->prop);
                continue;
            }
            sp<HalClientSpVector>& clients = mPropertyToClientsMap.editValueAt(index);
            EVENT_LOG("onHalEvents, prop 0x%x, active clients %d", e->prop, clients->size());
            for (size_t i = 0; i < clients->size(); i++) {
                sp<HalClient>& client = clients->editItemAt(i);
                activeClients.add(client);
                client->addEvent(e);
            }
        }
    } while (0);
    EVENT_LOG("onHalEvents num events %d, active clients:%d", events.size(), activeClients.size());
    for (size_t i = 0; i < activeClients.size(); i++) {
        sp<HalClient> client = activeClients.editItemAt(i);
        client->dispatchEvents();
    }
    activeClients.clear();
}

status_t VehicleNetworkService::loadHal() {
    int r = hw_get_module(VEHICLE_HARDWARE_MODULE_ID, (hw_module_t const**)&mModule);
    if (r != NO_ERROR) {
        ALOGE("cannot load HAL module, error:%d", r);
        return r;
    }
    r = mModule->common.methods->open(&mModule->common, VEHICLE_HARDWARE_DEVICE,
            (hw_device_t**)&mDevice);
    return r;
}

void VehicleNetworkService::closeHal() {
    mDevice->common.close(&mDevice->common);
}
};
