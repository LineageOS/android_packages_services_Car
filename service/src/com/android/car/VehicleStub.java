/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car;

import android.annotation.Nullable;
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.IVehicle;
import android.os.RemoteException;
import android.os.SystemProperties;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * VehicleStub represents an IVehicle service interface in either AIDL or legacy HIDL version. It
 * exposes common interface so that the client does not need to care about which version the
 * underlying IVehicle service is in.
 */
public class VehicleStub {
    private static final String AIDL_VHAL_SERVICE = "android.hardware.automotive.vehicle";

    private final IVehicle mAidlVehicle;
    private final android.hardware.automotive.vehicle.V2_0.IVehicle mHidlVehicle;

    public VehicleStub() {
        mAidlVehicle = getAidlVehicle();
        if (mAidlVehicle != null) {
            mHidlVehicle = null;
            return;
        }

        Slogf.w(CarLog.TAG_SERVICE, "No AIDL vehicle HAL found, fall back to HIDL version");
        mHidlVehicle = getHidlVehicle();
    }

    @VisibleForTesting
    public VehicleStub(IVehicle aidlVehicle) {
        mAidlVehicle = aidlVehicle;
        mHidlVehicle = null;
    }

    @VisibleForTesting
    public VehicleStub(android.hardware.automotive.vehicle.V2_0.IVehicle hidlVehicle) {
        mHidlVehicle = hidlVehicle;
        mAidlVehicle = null;
    }

    /**
     * Returns whether this vehicle stub is connecting to a valid vehicle HAL.
     *
     * @return Whether this vehicle stub is connecting to a valid vehicle HAL.
     */
    public boolean isValid() {
        return mAidlVehicle != null || mHidlVehicle != null;
    }

    /**
     * Gets the interface descriptor for the connecting vehicle HAL.
     *
     * @return the interface descriptor.
     * @throws IllegalStateException If unable to get the descriptor.
     */
    public String getInterfaceDescriptor() throws IllegalStateException {
        try {
            if (mAidlVehicle != null) {
                return mAidlVehicle.asBinder().getInterfaceDescriptor();
            }
            return mHidlVehicle.interfaceDescriptor();
        } catch (RemoteException e) {
            throw new IllegalStateException("Unable to get Vehicle HAL interface descriptor", e);
        }
    }

    /**
     * Register a death recipient that would be called when vehicle HAL died.
     *
     * @param recipient A death recipient.
     * @throws IllegalStateException If unable to register the death recipient.
     */
    public void linkToDeath(IVehicleDeathRecipient recipient) throws IllegalStateException {
        try {
            if (mAidlVehicle != null) {
                mAidlVehicle.asBinder().linkToDeath(recipient, /*flag=*/ 0);
                return;
            }
            mHidlVehicle.linkToDeath(recipient, /*flag=*/ 0);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to linkToDeath Vehicle HAL");
        }
    }

    /**
     * Unlink a previously linked death recipient.
     *
     * @param recipient A previously linked death recipient.
     */
    public void unlinkToDeath(IVehicleDeathRecipient recipient) {
        if (mAidlVehicle != null) {
            mAidlVehicle.asBinder().unlinkToDeath(recipient, /*flag=*/ 0);
            return;
        }

        try {
            mHidlVehicle.unlinkToDeath(recipient);
        } catch (RemoteException e) {
            // Ignore errors on shutdown path.
        }
    }

    /**
     * Get all property configs.
     *
     * @return All the property configs.
     */
    public ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropConfig> getAllPropConfigs()
            throws RemoteException {
        if (mAidlVehicle != null) {
            // TODO(b/205774940): Call AIDL APIs.
            return null;
        }
        return mHidlVehicle.getAllPropConfigs();
    }

    /**
     * Subscribe to a property.
     *
     * @param callback The IVehicleCallback that would be called for subscribe events.
     * @param options The list of subscribe options.
     * @throws RemoteException if the subscription fails.
     */
    public void subscribe(
            android.hardware.automotive.vehicle.V2_0.IVehicleCallback callback,
            ArrayList<android.hardware.automotive.vehicle.V2_0.SubscribeOptions> options)
            throws RemoteException {
        if (mAidlVehicle != null) {
            // TODO(b/205774940): Call AIDL APIs.
            return;
        }
        mHidlVehicle.subscribe(callback, options);
    }

    /**
     * Unsubscribe to a property.
     *
     * @param callback The previously subscribed callback to unsubscribe.
     * @param prop The ID for the property to unsubscribe.
     * @throws RemoteException if the unsubscription fails.
     */
    public void unsubscribe(
            android.hardware.automotive.vehicle.V2_0.IVehicleCallback callback, int prop)
            throws RemoteException {
        if (mAidlVehicle != null) {
            // TODO(b/205774940): Call AIDL APIs.
            return;
        }
        mHidlVehicle.unsubscribe(callback, prop);
    }

    /**
     * Get a property.
     *
     * @param requestedPropValue The property to get.
     * @param callback The callback to be called for the result.
     * @throws RemoteException if the operation fails.
     */
    public void get(
            android.hardware.automotive.vehicle.V2_0.VehiclePropValue requestedPropValue,
            android.hardware.automotive.vehicle.V2_0.IVehicle.getCallback callback)
            throws RemoteException {
        if (mAidlVehicle != null) {
            // TODO(b/205774940): Call AIDL APIs.
            return;
        }
        mHidlVehicle.get(requestedPropValue, callback);
    }

    /**
     * Set a property.
     *
     * @param propValue The property to set.
     * @return The status code.
     * @throws RemoteException if the operation fails.
     */
    public int set(android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue)
            throws RemoteException {
        if (mAidlVehicle != null) {
            // TODO(b/205774940): Call AIDL APIs.
            return 0;
        }
        return mHidlVehicle.set(propValue);
    }

    @Nullable
    private static android.hardware.automotive.vehicle.V2_0.IVehicle getHidlVehicle() {
        String instanceName = SystemProperties.get("ro.vehicle.hal", "default");

        try {
            return android.hardware.automotive.vehicle.V2_0.IVehicle.getService(instanceName);
        } catch (RemoteException e) {
            Slogf.e(CarLog.TAG_SERVICE, "Failed to get IVehicle/" + instanceName + " service", e);
        } catch (NoSuchElementException e) {
            Slogf.e(CarLog.TAG_SERVICE, "IVehicle/" + instanceName + " service not registered yet");
        }
        return null;
    }

    @Nullable
    private IVehicle getAidlVehicle() {
        try {
            return IVehicle.Stub.asInterface(
                    ServiceManagerHelper.waitForDeclaredService(AIDL_VHAL_SERVICE));
        } catch (RuntimeException e) {
            Slogf.w(CarLog.TAG_SERVICE, "Failed to get \"" + AIDL_VHAL_SERVICE + "\" service", e);
        }
        return null;
    }
}
