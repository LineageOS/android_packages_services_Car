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

#include <unistd.h>

#include <gtest/gtest.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include <utils/SystemClock.h>
#include <VehicleNetwork.h>

namespace android {

// Be careful with name conflict with other tests!. It can lead into wrong virtual function table
// , leading into mysterious crash. Always add test name in front for any class name.
class VehicleNetworkTestListener : public VehicleNetworkListener {
public:
    VehicleNetworkTestListener() {
        String8 msg;
        msg.appendFormat("Creating VehicleNetworkTestListener 0x%p\n", this);
        std::cout<<msg.string();
    }

    virtual ~VehicleNetworkTestListener() {
        std::cout<<"destroying VehicleNetworkTestListener\n";
    }

    virtual void onEvents(sp<VehiclePropValueListHolder>& events) {
        String8 msg("events ");
        Mutex::Autolock autolock(mLock);
        for (auto& e : events->getList()) {
            ssize_t index = mEventCounts.indexOfKey(e->prop);
            if (index < 0) {
                mEventCounts.add(e->prop, 1); // 1st event
                msg.appendFormat("0x%x:%d ", e->prop, 1);
            } else {
                int count = mEventCounts.valueAt(index);
                count++;
                mEventCounts.replaceValueAt(index, count);
                msg.appendFormat("0x%x:%d ", e->prop, count);
            }
        }
        msg.append("\n");
        std::cout<<msg.string();
        mCondition.signal();
    }

    void waitForEvents(nsecs_t reltime) {
        Mutex::Autolock autolock(mLock);
        mCondition.waitRelative(mLock, reltime);
    }

    bool waitForEvent(int32_t property, nsecs_t reltime) {
        Mutex::Autolock autolock(mLock);
        int startCount = getEventCountLocked(property);
        int currentCount = startCount;
        int64_t now = android::elapsedRealtimeNano();
        int64_t endTime = now + reltime;
        while ((startCount == currentCount) && (now < endTime)) {
            mCondition.waitRelative(mLock, endTime - now);
            currentCount = getEventCountLocked(property);
            now = android::elapsedRealtimeNano();
        }
        return (startCount != currentCount);
    }

    int getEventCount(int32_t property) {
        Mutex::Autolock autolock(mLock);
        return getEventCountLocked(property);
    }

private:
    int getEventCountLocked(int32_t property) {
        ssize_t index = mEventCounts.indexOfKey(property);
        if (index < 0) {
            return 0;
        } else {
            return mEventCounts.valueAt(index);
        }
    }
private:
    Mutex mLock;
    Condition mCondition;
    KeyedVector<int32_t, int> mEventCounts;
};

class VehicleNetworkTest : public testing::Test {
public:
    VehicleNetworkTest() :
        mVN(NULL),
        mListener(new VehicleNetworkTestListener()) {
        String8 msg;
        msg.appendFormat("Creating VehicleNetworkTest 0x%p %p %p\n", this, mVN.get(),
                mListener.get());
        std::cout<<msg.string();
    }

    virtual ~VehicleNetworkTest() { }

    sp<VehicleNetwork> getDefaultVN() {
        return mVN;
    }

    VehicleNetworkTestListener& getTestListener() {
        return *mListener.get();
    }

protected:
    void SetUp() {
        String8 msg;
        msg.appendFormat("setUp starts %p %p %p\n", this, mVN.get(),
                mListener.get());
        std::cout<<msg.string();
        ASSERT_TRUE(mListener.get() != NULL);
        sp<VehicleNetworkListener> listener(mListener.get());
        mVN = VehicleNetwork::createVehicleNetwork(listener);
        ASSERT_TRUE(mVN.get() != NULL);
        std::cout<<"setUp ends\n";
    }

protected:
    sp<VehicleNetwork> mVN;
    sp<VehicleNetworkTestListener> mListener;
};


TEST_F(VehicleNetworkTest, listProperties) {
    sp<VehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getNumConfigs();
    ASSERT_TRUE(numConfigs > 0);
    vehicle_prop_config_t const * config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        String8 msg = String8::format("prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        config++;
    }
    sp<VehiclePropertiesHolder> propertiesIvalid  = vn->listProperties(-1); // no such property
    ASSERT_TRUE(propertiesIvalid.get() == NULL);
    config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        String8 msg = String8::format("query single prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        sp<VehiclePropertiesHolder> singleProperty = vn->listProperties(config->prop);
        ASSERT_EQ(1, singleProperty->getNumConfigs());
        vehicle_prop_config_t const * newConfig = singleProperty->getData();
        ASSERT_EQ(config->prop, newConfig->prop);
        ASSERT_EQ(config->access, newConfig->access);
        ASSERT_EQ(config->change_mode, newConfig->change_mode);
        //TODO add more check
        config++;
    }
}

TEST_F(VehicleNetworkTest, getProperty) {
    sp<VehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getNumConfigs();
    ASSERT_TRUE(numConfigs > 0);
    vehicle_prop_config_t const * config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        String8 msg = String8::format("getting prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        ScopedVehiclePropValue value;
        value.value.prop = config->prop;
        status_t r = vn->getProperty(&value.value);
        if ((config->access & VEHICLE_PROP_ACCESS_READ) == 0) { // cannot read
            ASSERT_TRUE(r != NO_ERROR);
        } else {
            ASSERT_EQ(NO_ERROR, r);
            ASSERT_EQ(config->value_type, value.value.value_type);
        }
        config++;
    }
}

//TODO change this test to to safe write
TEST_F(VehicleNetworkTest, setProperty) {
    sp<VehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getNumConfigs();
    ASSERT_TRUE(numConfigs > 0);
    vehicle_prop_config_t const * config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        String8 msg = String8::format("setting prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        ScopedVehiclePropValue value;
        value.value.prop = config->prop;
        value.value.value_type = config->value_type;
        status_t r = vn->setProperty(value.value);
        if ((config->access & VEHICLE_PROP_ACCESS_WRITE) == 0) { // cannot write
            ASSERT_TRUE(r != NO_ERROR);
        } else {
            ASSERT_EQ(NO_ERROR, r);
        }
        config++;
    }
}

TEST_F(VehicleNetworkTest, setSubscribe) {
    sp<VehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getNumConfigs();
    ASSERT_TRUE(numConfigs > 0);
    vehicle_prop_config_t const * config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        String8 msg = String8::format("subscribing property 0x%x\n", config->prop);
        std::cout<<msg.string();
        status_t r = vn->subscribe(config->prop, config->max_sample_rate);
        if (((config->access & VEHICLE_PROP_ACCESS_READ) == 0) ||
                (config->change_mode == VEHICLE_PROP_CHANGE_MODE_STATIC)) { // cannot subsctibe
            ASSERT_TRUE(r != NO_ERROR);
        } else {
            ASSERT_EQ(NO_ERROR, r);
            ASSERT_TRUE(getTestListener().waitForEvent(config->prop, 2000000000));
        }
        config++;
    }
    config = properties->getData();
    for (int32_t i = 0; i < numConfigs; i++) {
        vn->unsubscribe(config->prop);
        config++;
    }
    usleep(1000000);
    config = properties->getData();
    //TODO improve this as this will wait for too long
    for (int32_t i = 0; i < numConfigs; i++) {
        ASSERT_TRUE(!getTestListener().waitForEvent(config->prop, 1000000000));
        config++;
    }
}

}; // namespace android
