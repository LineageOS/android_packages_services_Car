/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.vehiclenetwork.haltest;

import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_AC_ON;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_DEFROSTER;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_FAN_DIRECTION;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_FAN_SPEED;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_MAX_AC_ON;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_RECIRC_ON;

import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleHvacFanDirection;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;

/**
 * Test HVAC vehicle HAL using vehicle network service.
 */
public class HvacVnsHalTest extends VnsHalBaseTestCase {

    public void testAcProperty() throws Exception {
        int propertyId = VEHICLE_PROPERTY_HVAC_AC_ON;
        if (!isPropertyAvailable(propertyId)) {
            return;
        }

        VehiclePropConfig config = mConfigsMap.get(propertyId);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        int zone = getFirstZoneForProperty(propertyId);
        setPropertyAndVerify(propertyId, zone, false);

        mVehicleNetwork.subscribe(propertyId, 0, 0);
        mListener.reset();
        mListener.addExpectedValues(propertyId, zone, true);
        setPropertyAndVerify(propertyId, zone, true);
        mListener.waitAndVerifyValues();
    }

    public void testRecirculateProperty() {
        int propertyId = VEHICLE_PROPERTY_HVAC_RECIRC_ON;
        if (!isPropertyAvailable(propertyId)) {
            return;
        }
        VehiclePropConfig config = mConfigsMap.get(propertyId);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        // Verify subsequent calls ends-up with right value.
        mVehicleNetwork.setBooleanProperty(propertyId, false);
        mVehicleNetwork.setBooleanProperty(propertyId, true);
        mVehicleNetwork.setBooleanProperty(propertyId, false);
        mVehicleNetwork.setBooleanProperty(propertyId, true);
        verifyValue(propertyId, true);

        // Verify subsequent calls ends-up with right value.
        mVehicleNetwork.setBooleanProperty(propertyId, false);
        mVehicleNetwork.setBooleanProperty(propertyId, true);
        mVehicleNetwork.setBooleanProperty(propertyId, false);
        verifyValue(propertyId, false);

        setPropertyAndVerify(propertyId, false);
        setPropertyAndVerify(propertyId, true);
        setPropertyAndVerify(propertyId, false);
    }

    public void testMaxAcProperty() throws Exception {
        if (!isPropertyAvailable(
                VEHICLE_PROPERTY_HVAC_MAX_AC_ON,
                VEHICLE_PROPERTY_HVAC_AC_ON,
                VEHICLE_PROPERTY_HVAC_RECIRC_ON)) {
            return;
        }
        VehiclePropConfig config = mConfigsMap.get(VEHICLE_PROPERTY_HVAC_MAX_AC_ON);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        int acZone = getFirstZoneForProperty(VEHICLE_PROPERTY_HVAC_AC_ON);

        // Turn off related properties.
        setPropertyAndVerify(VEHICLE_PROPERTY_HVAC_MAX_AC_ON, false);
        setPropertyAndVerify(VEHICLE_PROPERTY_HVAC_AC_ON, acZone, false);
        setPropertyAndVerify(VEHICLE_PROPERTY_HVAC_RECIRC_ON, false);

        // Now turning max A/C and verify that other related HVAC props turned on.
        mVehicleNetwork.subscribe(VEHICLE_PROPERTY_HVAC_AC_ON, 0f, acZone);
        mVehicleNetwork.subscribe(VEHICLE_PROPERTY_HVAC_RECIRC_ON, 0f);
        mListener.reset();
        mListener.addExpectedValues(VEHICLE_PROPERTY_HVAC_AC_ON, acZone, true);
        mListener.addExpectedValues(VEHICLE_PROPERTY_HVAC_RECIRC_ON, 0, true);
        setPropertyAndVerify(VEHICLE_PROPERTY_HVAC_MAX_AC_ON, true);
        verifyValue(VEHICLE_PROPERTY_HVAC_AC_ON, acZone, true);
        verifyValue(VEHICLE_PROPERTY_HVAC_RECIRC_ON, true);
        mListener.waitAndVerifyValues();

        // When max A/C turned off, A/C should remain to be turned on, but circulation should has
        // the value it had before turning max A/C on, which if OFF in this case.
        setPropertyAndVerify(VEHICLE_PROPERTY_HVAC_MAX_AC_ON, false);
        verifyValue(VEHICLE_PROPERTY_HVAC_AC_ON, acZone, true);
        verifyValue(VEHICLE_PROPERTY_HVAC_RECIRC_ON, false);
    }

    public void testDefroster() {
        final int propertyId = VEHICLE_PROPERTY_HVAC_DEFROSTER;
        if (!isPropertyAvailable(propertyId)) {
            return;
        }

        VehiclePropConfig config = mConfigsMap.get(propertyId);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        iterateOnZones(config, (message, zone, minValue, maxValue) -> {
            Log.i(TAG, "testDefroster, " + message);
            setPropertyAndVerify(propertyId, zone, false);
            setPropertyAndVerify(propertyId, zone, true);
        });
    }

    public void testFanSpeed() throws Exception {
        if (!isPropertyAvailable(VEHICLE_PROPERTY_HVAC_FAN_SPEED)) {
            return;
        }

        VehiclePropConfig config = mConfigsMap.get(VEHICLE_PROPERTY_HVAC_FAN_SPEED);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        verifyIntZonedProperty(VEHICLE_PROPERTY_HVAC_FAN_SPEED);
    }

    public void testFanDirection() throws Exception {
        final int propertyId = VEHICLE_PROPERTY_HVAC_FAN_DIRECTION;

        if (!isPropertyAvailable(propertyId)) {
            return;
        }

        VehiclePropConfig config = mConfigsMap.get(propertyId);
        assertEquals(VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32, config.getValueType());
        assertEquals(VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE, config.getAccess());

        // Assert setting edge-case values.
        iterateOnZones(config, (message, zone, minValue, maxValue) -> {
            setPropertyAndVerify(message, propertyId, zone, minValue);
            setPropertyAndVerify(message, propertyId, zone, maxValue);
        });

        iterateOnZones(config, ((message, zone, minValue, maxValue)
                -> setPropertyAndVerify(message, propertyId, zone,
                        VehicleHvacFanDirection.VEHICLE_HVAC_FAN_DIRECTION_DEFROST_AND_FLOOR)));
    }
}
