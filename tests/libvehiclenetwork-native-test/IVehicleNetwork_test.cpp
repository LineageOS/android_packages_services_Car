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
#include <IVehicleNetwork.h>

namespace android {

class IVehicleNetworkTest : public testing::Test {
public:
    IVehicleNetworkTest() {}

    ~IVehicleNetworkTest() {}

    sp<IVehicleNetwork> connectToService() {
        sp<IBinder> binder = defaultServiceManager()->getService(
                String16(IVehicleNetwork::SERVICE_NAME));
        if (binder != NULL) {
            sp<IVehicleNetwork> vn(interface_cast<IVehicleNetwork>(binder));
            return vn;
        }
        sp<IVehicleNetwork> dummy;
        return dummy;
    }

    sp<IVehicleNetwork> getDefaultVN() {
        return mDefaultVN;
    }
protected:
    virtual void SetUp() {
        ProcessState::self()->startThreadPool();
        mDefaultVN = connectToService();
        ASSERT_TRUE(mDefaultVN.get() != NULL);
    }
private:
    sp<IVehicleNetwork> mDefaultVN;
};

class IVehicleNetworkTestTestListener : public BnVehicleNetworkListener {
public:
    virtual status_t onEvents(sp<VehiclePropValueListHolder>& events) {
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
        return NO_ERROR;
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

TEST_F(IVehicleNetworkTest, connect) {
    sp<IVehicleNetwork> vn = connectToService();
    ASSERT_TRUE(vn.get() != NULL);
}

TEST_F(IVehicleNetworkTest, listProperties) {
    sp<IVehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getList().size();
    ASSERT_TRUE(numConfigs > 0);
    for (auto& config : properties->getList()) {
        String8 msg = String8::format("prop 0x%x\n", config->prop);
        std::cout<<msg.string();
    }
    sp<VehiclePropertiesHolder> propertiesIvalid  = vn->listProperties(-1); // no such property
    ASSERT_TRUE(propertiesIvalid.get() == NULL);
    for (auto& config : properties->getList()) {
        String8 msg = String8::format("query single prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        sp<VehiclePropertiesHolder> singleProperty = vn->listProperties(config->prop);
        ASSERT_EQ(1, singleProperty->getList().size());
        vehicle_prop_config_t const * newConfig = *singleProperty->getList().begin();
        ASSERT_EQ(config->prop, newConfig->prop);
        ASSERT_EQ(config->access, newConfig->access);
        ASSERT_EQ(config->change_mode, newConfig->change_mode);
        //TODO add more check
    }
}

TEST_F(IVehicleNetworkTest, getProperty) {
    sp<IVehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getList().size();
    ASSERT_TRUE(numConfigs > 0);
    for (auto& config : properties->getList()) {
        String8 msg = String8::format("getting prop 0x%x\n", config->prop);
        std::cout<<msg.string();
        if ((config->prop >= (int32_t)VEHICLE_PROPERTY_INTERNAL_START) &&
                (config->prop <= (int32_t)VEHICLE_PROPERTY_INTERNAL_END)) {
            // internal property requires write to get anything.
            ScopedVehiclePropValue value;
            value.value.prop = config->prop;
            value.value.value_type = config->value_type;
            status_t r = vn->setProperty(value.value);
            ASSERT_EQ(NO_ERROR, r);
        }
        ScopedVehiclePropValue value;
        value.value.prop = config->prop;
        status_t r = vn->getProperty(&value.value);
        if ((config->access & VEHICLE_PROP_ACCESS_READ) == 0) { // cannot read
            ASSERT_TRUE(r != NO_ERROR);
        } else {
            ASSERT_EQ(NO_ERROR, r);
            ASSERT_EQ(config->value_type, value.value.value_type);
        }
    }
}

//TODO change this test to to safe write
TEST_F(IVehicleNetworkTest, setProperty) {
    sp<IVehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getList().size();
    ASSERT_TRUE(numConfigs > 0);
    for (auto& config : properties->getList()) {
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
    }
}

TEST_F(IVehicleNetworkTest, setSubscribe) {
    sp<IVehicleNetwork> vn = getDefaultVN();
    sp<VehiclePropertiesHolder> properties = vn->listProperties();
    ASSERT_TRUE(properties.get() != NULL);
    int32_t numConfigs = properties->getList().size();
    ASSERT_TRUE(numConfigs > 0);
    sp<IVehicleNetworkTestTestListener> listener(new IVehicleNetworkTestTestListener());
    for (auto& config : properties->getList()) {
        String8 msg = String8::format("subscribing property 0x%x\n", config->prop);
        std::cout<<msg.string();
        status_t r = vn->subscribe(listener, config->prop, config->max_sample_rate);
        if (((config->access & VEHICLE_PROP_ACCESS_READ) == 0) ||
                (config->change_mode == VEHICLE_PROP_CHANGE_MODE_STATIC)) { // cannot subsctibe
            ASSERT_TRUE(r != NO_ERROR);
        } else {
            if ((config->prop >= (int32_t)VEHICLE_PROPERTY_INTERNAL_START) &&
                    (config->prop <= (int32_t)VEHICLE_PROPERTY_INTERNAL_END)) {
                // internal property requires write for event notification.
                ScopedVehiclePropValue value;
                value.value.prop = config->prop;
                value.value.value_type = config->value_type;
                status_t r = vn->setProperty(value.value);
                ASSERT_EQ(NO_ERROR, r);
            }
            ASSERT_EQ(NO_ERROR, r);
            ASSERT_TRUE(listener->waitForEvent(config->prop, 2000000000));
        }
    }
    for (auto& config : properties->getList()) {
        vn->unsubscribe(listener, config->prop);
    }
    usleep(1000000);
    //TODO improve this as this will wait for too long
    for (auto& config : properties->getList()) {
        ASSERT_TRUE(!listener->waitForEvent(config->prop, 1000000000));
    }
}

}; // namespace android
