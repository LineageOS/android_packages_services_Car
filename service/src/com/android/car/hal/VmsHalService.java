/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.car.hal;

import static com.android.car.CarServiceUtils.toByteArray;
import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VmsMessageIntegerValuesIndex;
import android.hardware.automotive.vehicle.V2_0.VmsMessageType;
import android.util.Log;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This is a glue layer between the VehicleHal and the VmsService. It sends VMS properties back and
 * forth.
 */
@FutureFeature
public class VmsHalService extends HalServiceBase {
    private static final boolean DBG = true;
    private static final int HAL_PROPERTY_ID = VehicleProperty.VEHICLE_MAP_SERVICE;
    private static final String TAG = "VmsHalService";
    private static final Set<Integer> SUPPORTED_MESSAGE_TYPES =
        new HashSet<Integer>(
            Arrays.asList(
                VmsMessageType.SUBSCRIBE,
                VmsMessageType.UNSUBSCRIBE,
                VmsMessageType.DATA));

    private boolean mIsSupported = false;
    private CopyOnWriteArrayList<VmsHalPublisherListener> mPublisherListeners =
        new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<VmsHalSubscriberListener> mSubscriberListeners =
        new CopyOnWriteArrayList<>();
    private final VehicleHal mVehicleHal;

    /**
     * The VmsPublisherService implements this interface to receive data from the HAL.
     */
    public interface VmsHalPublisherListener {
        void onChange(int layerId, int layerVersion, boolean hasSubscribers);
    }

    /**
     * The VmsSubscriberService implements this interface to receive data from the HAL.
     */
    public interface VmsHalSubscriberListener {
        void onChange(int layerId, int layerVersion, byte[] payload);
    }

    /**
     * The VmsService implements this interface to receive data from the HAL.
     */
    protected VmsHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
        if (DBG) {
            Log.d(TAG, "started VmsHalService!");
        }
    }

    public void addPublisherListener(VmsHalPublisherListener listener) {
        mPublisherListeners.add(listener);
    }

    public void addSubscriberListener(VmsHalSubscriberListener listener) {
        mSubscriberListeners.add(listener);
    }

    public void removePublisherListener(VmsHalPublisherListener listener) {
        mPublisherListeners.remove(listener);
    }

    public void removeSubscriberListener(VmsHalSubscriberListener listener) {
        mSubscriberListeners.remove(listener);
    }

    @Override
    public void init() {
        if (DBG) {
            Log.d(TAG, "init()");
        }
        if (mIsSupported) {
            mVehicleHal.subscribeProperty(this, HAL_PROPERTY_ID, 0);
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Log.d(TAG, "release()");
        }
        if (mIsSupported) {
            mVehicleHal.unsubscribeProperty(this, HAL_PROPERTY_ID);
        }
        mPublisherListeners.clear();
        mSubscriberListeners.clear();
    }

    @Override
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<>();
        for (VehiclePropConfig p : allProperties) {
            if (p.prop == HAL_PROPERTY_ID) {
                taken.add(p);
                mIsSupported = true;
                if (DBG) {
                    Log.d(TAG, "takeSupportedProperties: " + toHexString(p.prop));
                }
                break;
            }
        }
        return taken;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        for (VehiclePropValue v : values) {
            ArrayList<Integer> vec = v.value.int32Values;
            int messageType = vec.get(VmsMessageIntegerValuesIndex.VMS_MESSAGE_TYPE);
            int layerId = vec.get(VmsMessageIntegerValuesIndex.VMS_LAYER_ID);
            int layerVersion = vec.get(VmsMessageIntegerValuesIndex.VMS_LAYER_VERSION);

            // Check if message type is supported.
            if (!SUPPORTED_MESSAGE_TYPES.contains(messageType)) {
                throw new IllegalArgumentException("Unexpected message type. " +
                    "Expecting: " + SUPPORTED_MESSAGE_TYPES +
                    ". Got: " + messageType);

            }

            // This is a data message intended for subscribers.
            if (messageType == VmsMessageType.DATA) {
                // Get the payload.
                byte[] payload = toByteArray(v.value.bytes);

                // Send the message.
                for (VmsHalSubscriberListener listener : mSubscriberListeners) {
                    listener.onChange(layerId, layerVersion, payload);
                }
            } else {
                //TODO(b/35386660): This is placeholder until implementing subscription manager.
                // Get subscribe or unsubscribe.
                boolean hasSubscribers = (messageType == VmsMessageType.SUBSCRIBE) ? true : false;

                // Send the message.
                for (VmsHalPublisherListener listener : mPublisherListeners) {
                    listener.onChange(layerId, layerVersion, hasSubscribers);
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("VmsProperty " + (mIsSupported ? "" : "not") + " supported.");
    }

    /**
     * Updates the VMS HAL property with the given value.
     *
     * @param property the value used to update the HAL property.
     * @return         true if the call to the HAL to update the property was successful.
     */
    public boolean setSubscribeRequest(int layerId, int layerVersion) {
        VehiclePropValue vehiclePropertyValue = toVehiclePropValue(VmsMessageType.SUBSCRIBE,
            layerId,
            layerVersion);
        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setUnsubscribeRequest(int layerId, int layerVersion) {
        VehiclePropValue vehiclePropertyValue = toVehiclePropValue(VmsMessageType.UNSUBSCRIBE,
            layerId,
            layerVersion);
        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setDataMessage(int layerId, int layerVersion, byte[] payload) {
        VehiclePropValue vehiclePropertyValue = toVehiclePropValue(VmsMessageType.DATA,
            layerId,
            layerVersion,
            payload);
        // TODO(b/34977500): remove call to handleHalEvents once the routing is implemented.
        // This temporal code forwards messages from publishers to subscribers.
        List<VehiclePropValue> list = new ArrayList<>();
        list.add(vehiclePropertyValue);
        handleHalEvents(list);
        return setPropertyValue(vehiclePropertyValue);
    }

    public boolean setPropertyValue(VehiclePropValue vehiclePropertyValue) {
        try {
            mVehicleHal.set(vehiclePropertyValue);
            return true;
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_PROPERTY, "set, property not ready 0x" + toHexString(HAL_PROPERTY_ID));
        }
        return false;
    }

    /** Creates a {@link VehiclePropValue} */
    static VehiclePropValue toVehiclePropValue(int messageType,
                                               int layerId,
                                               int layerVersion) {
        VehiclePropValue vehicleProp = new VehiclePropValue();
        vehicleProp.prop = HAL_PROPERTY_ID;
        vehicleProp.areaId = VehicleAreaType.VEHICLE_AREA_TYPE_NONE;
        VehiclePropValue.RawValue v = vehicleProp.value;

        v.int32Values.add(messageType);
        v.int32Values.add(layerId);
        v.int32Values.add(layerVersion);
        return vehicleProp;
    }

    /** Creates a {@link VehiclePropValue} with payload*/
    static VehiclePropValue toVehiclePropValue(int messageType,
                                               int layerId,
                                               int layerVersion,
                                               byte[] payload) {
        VehiclePropValue vehicleProp = toVehiclePropValue(messageType, layerId, layerVersion);
        VehiclePropValue.RawValue v = vehicleProp.value;
        v.bytes.ensureCapacity(payload.length);
        for (byte b : payload) {
            v.bytes.add(b);
        }
        return vehicleProp;
    }
}
