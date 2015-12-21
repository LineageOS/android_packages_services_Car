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
package com.android.car.vehiclenetwork;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;
import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;

/**
 * System API to access Vehicle network. This is only for system services and applications should
 * not use this. All APIs will fail with security error if normal app tries this.
 */
public class VehicleNetwork {
    /**
     * Listener for VNS events.
     */
    public interface VehicleNetworkListener {
        /**
         * Notify HAL events. This requires subscribing the property
         */
        void onVehicleNetworkEvents(VehiclePropValues values);
        void onHalError(int errorCode, int property, int operation);
        void onHalRestart(boolean inMocking);
    }

    public interface VehicleNetworkHalMock {
        VehiclePropConfigs onListProperties();
        void onPropertySet(VehiclePropValue value);
        VehiclePropValue onPropertyGet(VehiclePropValue value);
        void onPropertySubscribe(int property, float sampleRate, int zones);
        void onPropertyUnsubscribe(int property);
    }

    private static final String TAG = VehicleNetwork.class.getSimpleName();

    private final IVehicleNetwork mService;
    private final VehicleNetworkListener mListener;
    private final IVehicleNetworkListenerImpl mVehicleNetworkListener;
    private final EventHandler mEventHandler;

    @GuardedBy("this")
    private VehicleNetworkHalMock mHalMock;
    private IVehicleNetworkHalMock mHalMockImpl;

    private static final int VNS_CONNECT_MAX_RETRY = 10;
    private static final long VNS_RETRY_WAIT_TIME_MS = 1000;

    /**
     * Factory method to create VehicleNetwork
     * @param listener listener for listening events
     * @param looper Looper to dispatch listener events
     * @return
     */
    public static VehicleNetwork createVehicleNetwork(VehicleNetworkListener listener,
            Looper looper) {
        int retryCount = 0;
        IVehicleNetwork service = null;
        while (service == null) {
            service = IVehicleNetwork.Stub.asInterface(ServiceManager.getService(
                    IVehicleNetwork.class.getCanonicalName()));
            retryCount++;
            if (retryCount > VNS_CONNECT_MAX_RETRY) {
                break;
            }
            try {
                Thread.sleep(VNS_RETRY_WAIT_TIME_MS);
            } catch (InterruptedException e) {
                //ignore
            }
        }
        if (service == null) {
            throw new RuntimeException("Vehicle network service not available:" +
                    IVehicleNetwork.class.getCanonicalName());
        }
        return new VehicleNetwork(service, listener, looper);
    }

    private VehicleNetwork(IVehicleNetwork service, VehicleNetworkListener listener,
            Looper looper) {
        mService = service;
        mListener = listener;
        mEventHandler = new EventHandler(looper);
        mVehicleNetworkListener = new IVehicleNetworkListenerImpl(this);
    }

    /**
     * List all properties from vehicle HAL
     * @return all properties
     */
    public VehiclePropConfigs listProperties() {
        return listProperties(0 /* all */);
    }

    /**
     * Return configuration information of single property
     * @param property vehicle property number defined in {@link VehicleNetworkConsts}. 0 has
     *        has special meaning of list all properties.
     * @return null if given property does not exist.
     */
    public VehiclePropConfigs listProperties(int property) {
        try {
            VehiclePropConfigsParcelable parcelable = mService.listProperties(property);
            if (parcelable != null) {
                return parcelable.configs;
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return null;
    }

    /**
     * Set property which will lead into writing the value to vehicle HAL.
     * @param value
     * @throws IllegalArgumentException If value set has wrong value like wrong valueType,
     *         wrong data, and etc.
     */
    public void setProperty(VehiclePropValue value) throws IllegalArgumentException {
        VehiclePropValueParcelable parcelable = new VehiclePropValueParcelable(value);
        try {
            mService.setProperty(parcelable);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Set integer type property
     * @param property
     * @param value
     * @throws IllegalArgumentException For type mismatch (=the property is not int type)
     */
    public void setIntProperty(int property, int value) throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createIntValue(property, value, 0);
        setProperty(v);
    }

    /**
     * Set int vector type property. Length of passed values should match with vector length.
     * @param property
     * @param values
     * @throws IllegalArgumentException
     */
    public void setIntVectorProperty(int property, int[] values) throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createIntVectorValue(property, values, 0);
        setProperty(v);
    }

    /**
     * Set long type property.
     * @param property
     * @param value
     * @throws IllegalArgumentException
     */
    public void setLongProperty(int property, long value) throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createLongValue(property, value, 0);
        setProperty(v);
    }

    /**
     * Set float type property.
     * @param property
     * @param value
     * @throws IllegalArgumentException
     */
    public void setFloatProperty(int property, float value) throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createFloatValue(property, value, 0);
        setProperty(v);
    }

    /**
     * Set float vector type property. Length of values should match with vector length.
     * @param property
     * @param values
     * @throws IllegalArgumentException
     */
    public void setFloatVectorProperty(int property, float[] values)
            throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createFloatVectorValue(property, values, 0);
        setProperty(v);
    }

    /**
     * Set zoned boolean type property
     * @param property
     * @param zone
     * @param value
     * @throws IllegalArgumentException For type mismatch (=the property is not boolean type)
     */
    public void setZonedBooleanProperty(int property, int zone, boolean value)
            throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createZonedBooleanValue(property, zone, value, 0);
        setProperty(v);
    }

    /**
     * Set zoned float type property
     * @param property
     * @param zone
     * @param value
     * @throws IllegalArgumentException For type mismatch (=the property is not float type)
     */
    public void setZonedFloatProperty(int property, int zone, float value)
            throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createZonedFloatValue(property, zone, value, 0);
        setProperty(v);
    }

    /**
     * Set zoned integer type property
     * @param property
     * @param zone
     * @param value
     * @throws IllegalArgumentException For type mismatch (=the property is not int type)
     */
    public void setZonedIntProperty(int property, int zone, int value)
            throws IllegalArgumentException {
        VehiclePropValue v = VehiclePropValueUtil.createZonedIntValue(property, zone, value, 0);
        setProperty(v);
    }

    /**
     * Get property. This can be used for a property which does not require any other data.
     * @param property
     * @return
     * @throws IllegalArgumentException
     */
    public VehiclePropValue getProperty(int property) throws IllegalArgumentException {
        int valueType = VehicleNetworkConsts.getVehicleValueType(property);
        VehiclePropValue value = VehiclePropValueUtil.createBuilder(property, valueType, 0).build();
        return getProperty(value);
    }

    /**
     * Generic get method for any type of property. Some property may require setting data portion
     * as get may return different result depending on the data set.
     * @param value
     * @return
     * @throws IllegalArgumentException
     */
    public VehiclePropValue getProperty(VehiclePropValue value) throws IllegalArgumentException {
        VehiclePropValueParcelable parcelable = new VehiclePropValueParcelable(value);
        try {
            VehiclePropValueParcelable resParcelable = mService.getProperty(parcelable);
            if (resParcelable != null) {
                return resParcelable.value;
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return null;
    }

    /**
     * get int type property
     * @param property
     * @return
     * @throws IllegalArgumentException
     */
    public int getIntProperty(int property) throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            // if property is invalid, IllegalArgumentException should have been thrown
            // from getProperty.
            throw new IllegalStateException();
        }
        if (v.getValueType() != VehicleValueType.VEHICLE_VALUE_TYPE_INT32) {
            throw new IllegalArgumentException();
        }
        if (v.getInt32ValuesCount() != 1) {
            throw new IllegalStateException();
        }
        return v.getInt32Values(0);
    }

    /**
     * get int vector type property. Length of values should match vector length.
     * @param property
     * @throws IllegalArgumentException
     */
    public int[] getIntVectorProperty(int property) throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            // if property is invalid, IllegalArgumentException should have been thrown
            // from getProperty.
            throw new IllegalStateException();
        }
        switch (v.getValueType()) {
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2:
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC3:
            case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4:
                break;
            default:
                throw new IllegalArgumentException();
        }
        int[] values = new int[v.getValueType() - VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2 +
                               2];
        if (v.getInt32ValuesCount() != values.length) {
            throw new IllegalStateException();
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = v.getInt32Values(i);
        }
        return values;
    }

    /**
     * Get float type property.
     * @param property
     * @return
     * @throws IllegalArgumentException
     */
    public float getFloatProperty(int property) throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            throw new IllegalStateException();
        }
        if (v.getValueType() != VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT) {
            throw new IllegalArgumentException();
        }
        if (v.getFloatValuesCount() != 1) {
            throw new IllegalStateException();
        }
        return v.getFloatValues(0);
    }

    /**
     * Get float vector type property. Length of values should match vector's length.
     * @param property
     * @throws IllegalArgumentException
     */
    public float[] getFloatVectorProperty(int property)
            throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            // if property is invalid, IllegalArgumentException should have been thrown
            // from getProperty.
            throw new IllegalStateException();
        }
        switch (v.getValueType()) {
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC2:
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC3:
            case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC4:
                break;
            default:
                throw new IllegalArgumentException();
        }
        float[] values = new float[v.getValueType() -
                                   VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC2 + 2];
        if (v.getFloatValuesCount() != values.length) {
            throw new IllegalStateException();
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = v.getFloatValues(i);
        }
        return values;
    }

    /**
     * Get long type property.
     * @param property
     * @return
     * @throws IllegalArgumentException
     */
    public long getLongProperty(int property) throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            throw new IllegalStateException();
        }
        if (v.getValueType() != VehicleValueType.VEHICLE_VALUE_TYPE_INT64) {
            throw new IllegalArgumentException();
        }
        return v.getInt64Value();
    }

    /**
     * Get string type property.
     * @param property
     * @return
     * @throws IllegalArgumentException
     */
    //TODO check UTF8 to java string conversion
    public String getStringProperty(int property) throws IllegalArgumentException {
        VehiclePropValue v = getProperty(property);
        if (v == null) {
            throw new IllegalStateException();
        }
        if (v.getValueType() != VehicleValueType.VEHICLE_VALUE_TYPE_STRING) {
            throw new IllegalArgumentException();
        }
        return v.getStringValue();
    }

    /**
     * Subscribe given property with given sample rate.
     * @param property
     * @param sampleRate
     * @throws IllegalArgumentException
     */
    public void subscribe(int property, float sampleRate) throws IllegalArgumentException {
        subscribe(property, sampleRate, 0);
    }

    /**
     * Subscribe given property with given sample rate.
     * @param property
     * @param sampleRate
     * @throws IllegalArgumentException
     */
    public void subscribe(int property, float sampleRate, int zones)
            throws IllegalArgumentException {
        try {
            mService.subscribe(mVehicleNetworkListener, property, sampleRate, zones);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Stop subscribing the property.
     * @param property
     */
    public void unsubscribe(int property) {
        try {
            mService.unsubscribe(mVehicleNetworkListener, property);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Inject given value to all clients subscribing the property. This is for testing.
     * @param value
     */
    public synchronized void injectEvent(VehiclePropValue value) {
        try {
            mService.injectEvent(new VehiclePropValueParcelable(value));
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Start mocking of vehicle HAL. For testing only.
     * @param mock
     */
    public synchronized void startMocking(VehicleNetworkHalMock mock) {
        mHalMock = mock;
        mHalMockImpl = new IVehicleNetworkHalMockImpl(this);
        try {
            mService.startMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Stop mocking of vehicle HAL. For testing only.
     */
    public synchronized void stopMocking() {
        if (mHalMockImpl == null) {
            return;
        }
        try {
            mService.stopMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        } finally {
            mHalMock = null;
            mHalMockImpl = null;
        }
    }

    /**
     * Start mocking of vehicle HAL. For testing only.
     * @param mock
     */
    public synchronized void startMocking(IVehicleNetworkHalMock mock) {
        mHalMock = null;
        mHalMockImpl = mock;
        try {
            mService.startMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Stop mocking of vehicle HAL. For testing only.
     */
    public synchronized void stopMocking(IVehicleNetworkHalMock mock) {
        if (mock.asBinder() != mHalMockImpl.asBinder()) {
            return;
        }
        try {
            mService.stopMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        } finally {
            mHalMock = null;
            mHalMockImpl = null;
        }
    }

    public synchronized void injectHalError(int errorCode, int property, int operation) {
        try {
            mService.injectHalError(errorCode, property, operation);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public synchronized void startErrorListening() {
        try {
            mService.startErrorListening(mVehicleNetworkListener);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public synchronized void stopErrorListening() {
        try {
            mService.stopErrorListening(mVehicleNetworkListener);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public synchronized void startHalRestartMonitoring() {
        try {
            mService.startHalRestartMonitoring(mVehicleNetworkListener);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public synchronized void stopHalRestartMonitoring() {
        try {
            mService.stopHalRestartMonitoring(mVehicleNetworkListener);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private synchronized VehicleNetworkHalMock getHalMock() {
        return mHalMock;
    }

    private void handleRemoteException(RemoteException e) {
        throw new RuntimeException("Vehicle network service not working ", e);
    }

    private void handleVehicleNetworkEvents(VehiclePropValues values) {
        mListener.onVehicleNetworkEvents(values);
    }

    private void handleHalError(int errorCode, int property, int operation) {
        mListener.onHalError(errorCode, property, operation);
    }

    private void handleHalRestart(boolean inMocking) {
        mListener.onHalRestart(inMocking);
    }

    private class EventHandler extends Handler {
        private static final int MSG_EVENTS = 0;
        private static final int MSG_HAL_ERROR = 1;
        private static final int MSG_HAL_RESTART = 2;

        private EventHandler(Looper looper) {
            super(looper);
        }

        private void notifyEvents(VehiclePropValues values) {
            Message msg = obtainMessage(MSG_EVENTS, values);
            sendMessage(msg);
        }

        private void notifyHalError(int errorCode, int property, int operation) {
            Message msg = obtainMessage(MSG_HAL_ERROR, errorCode, property,
                    Integer.valueOf(operation));
            sendMessage(msg);
        }

        private void notifyHalRestart(boolean inMocking) {
            Message msg = obtainMessage(MSG_HAL_RESTART, inMocking ? 1 : 0, 0);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_EVENTS:
                    handleVehicleNetworkEvents((VehiclePropValues)msg.obj);
                    break;
                case MSG_HAL_ERROR:
                    handleHalError(msg.arg1, msg.arg2, (Integer)msg.obj);
                    break;
                case MSG_HAL_RESTART:
                    handleHalRestart(msg.arg1 == 1);
                    break;
                default:
                    Log.w(TAG, "unown message:" + msg.what, new RuntimeException());
                    break;
            }
        }
    }

    private static class IVehicleNetworkListenerImpl extends IVehicleNetworkListener.Stub {
        private final WeakReference<VehicleNetwork> mVehicleNetwork;

        private IVehicleNetworkListenerImpl(VehicleNetwork vehicleNewotk) {
            mVehicleNetwork = new WeakReference<VehicleNetwork>(vehicleNewotk);
        }

        @Override
        public void onVehicleNetworkEvents(VehiclePropValuesParcelable values) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork != null) {
                vehicleNetwork.mEventHandler.notifyEvents(values.values);
            }
        }

        @Override
        public void onHalError(int errorCode, int property, int operation) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork != null) {
                vehicleNetwork.mEventHandler.notifyHalError(errorCode, property, operation);
            }
        }

        @Override
        public void onHalRestart(boolean inMocking) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork != null) {
                vehicleNetwork.mEventHandler.notifyHalRestart(inMocking);
            }
        }
    }

    private static class IVehicleNetworkHalMockImpl extends IVehicleNetworkHalMock.Stub {
        private final WeakReference<VehicleNetwork> mVehicleNetwork;

        private IVehicleNetworkHalMockImpl(VehicleNetwork vehicleNewotk) {
            mVehicleNetwork = new WeakReference<VehicleNetwork>(vehicleNewotk);
        }

        @Override
        public VehiclePropConfigsParcelable onListProperties() {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork == null) {
                return null;
            }
            VehiclePropConfigs configs = vehicleNetwork.getHalMock().onListProperties();
            return new VehiclePropConfigsParcelable(configs);
        }

        @Override
        public void onPropertySet(VehiclePropValueParcelable value) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork == null) {
                return;
            }
            vehicleNetwork.getHalMock().onPropertySet(value.value);
        }

        @Override
        public VehiclePropValueParcelable onPropertyGet(VehiclePropValueParcelable value) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork == null) {
                return null;
            }
            VehiclePropValue resValue = vehicleNetwork.getHalMock().onPropertyGet(value.value);
            return new VehiclePropValueParcelable(resValue);
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork == null) {
                return;
            }
            vehicleNetwork.getHalMock().onPropertySubscribe(property, sampleRate, zones);
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            VehicleNetwork vehicleNetwork = mVehicleNetwork.get();
            if (vehicleNetwork == null) {
                return;
            }
            vehicleNetwork.getHalMock().onPropertyUnsubscribe(property);
        }
    }
}
