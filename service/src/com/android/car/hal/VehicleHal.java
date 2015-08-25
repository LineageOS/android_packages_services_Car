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

package com.android.car.hal;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.car.CarSensorEvent;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;

import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.List;

/**
 * Abstraction for vehicle HAL. This class handles interface with native HAL and do basic parsing
 * of received data (type check). Then each event is sent to corresponding {@link HalServiceBase}
 * implementation. It is responsibility of {@link HalServiceBase} to convert data to corresponding
 * Car*Service for Car*Manager API.
 */
public class VehicleHal {

    private static final boolean DBG = true;

    /**
     * Interface for mocking Hal which is used for testing, but should be kept even in release.
     */
    public interface HalMock {
        //TODO
    };

    static {
        System.loadLibrary("jni_carservice");
    }

    private static VehicleHal sInstance;

    private final HandlerThread mHandlerThread;
    private final DefaultHandler mDefaultHandler;

    public static synchronized VehicleHal getInstance(Context applicationContext) {
        if (sInstance == null) {
            sInstance = new VehicleHal(applicationContext);
            // init is handled in a separate thread to prevent blocking the calling thread for too
            // long.
            sInstance.startInit();
        }
        return sInstance;
    }

    public static synchronized void releaseInstance() {
        if (sInstance != null) {
            sInstance.release();
            sInstance = null;
        }
    }

    private final SensorHalService mSensorHal;
    private final InfoHalService mInfoHal;
    @SuppressWarnings({"UnusedDeclaration"})
    private long mNativePtr; // used by native code

    private static final int NUM_MAX_PROPERTY_ENTRIES = 100;
    private static final int NUM_MAX_DATA_ENTRIES = 1000;
    /**
     * Contain property - data type - int length - float length. Hal will fill this array
     * before calling {@link #onHalDataEvents(int)}.
     */
    private final int[] mNativepPropertyInfos = new int[4 * NUM_MAX_PROPERTY_ENTRIES];
    /**
     * Contains timestamp for all events. Hal will fill this in native side.
     */
    private final long[] mNativeTimestampsNs = new long[NUM_MAX_PROPERTY_ENTRIES];
    /**
     * Contain int data passed for HAL events.
     */
    private final int[] mNativeIntData = new int[NUM_MAX_DATA_ENTRIES];
    private final float[] mNativeFloatData = new float[NUM_MAX_DATA_ENTRIES];

    /** stores handler for each HAL property. Property events are sent to handler. */
    private final SparseArray<HalServiceBase> mPropertyHandlers = new SparseArray<HalServiceBase>();
    /** This is for iterating all HalServices with fixed order. */
    private final HalServiceBase[] mAllServices;

    private VehicleHal(Context applicationContext) {
        mHandlerThread = new HandlerThread("HAL");
        mHandlerThread.start();
        mDefaultHandler = new DefaultHandler(mHandlerThread.getLooper());
        // passing this should be safe as long as it is just kept and not used in constructor
        mSensorHal = new SensorHalService(this);
        mInfoHal = new InfoHalService(this);
        mAllServices = new HalServiceBase[] {
                mInfoHal,
                mSensorHal };
    }

    private void startInit() {
        mDefaultHandler.requestInit();
    }

    private void doInit() {
        mNativePtr = nativeInit(mNativepPropertyInfos, mNativeTimestampsNs, mNativeIntData,
                mNativeFloatData);
        HalProperty[] properties = getSupportedProperties(mNativePtr);
        LinkedList<HalProperty> allProperties = new LinkedList<HalProperty>();
        for (HalProperty prop : properties) {
            allProperties.add(prop);
            if (DBG) {
                Log.i(CarLog.TAG_HAL, "property: " + prop);
            }
        }
        for (HalServiceBase service: mAllServices) {
            List<HalProperty> taken = service.takeSupportedProperties(allProperties);
            if (taken == null) {
                continue;
            }
            if (DBG) {
                Log.i(CarLog.TAG_HAL, "HalService " + service + " take properties " + taken.size());
            }
            for (HalProperty p: taken) {
                mPropertyHandlers.append(p.propertyType, service);
            }
            allProperties.removeAll(taken);
            service.init();
        }
    }

    private void release() {
        // release in reverse order from init
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        mHandlerThread.quit();
        nativeRelease(mNativePtr);
    }

    public SensorHalService getSensorHal() {
        return mSensorHal;
    }

    public InfoHalService getInfoHal() {
        return mInfoHal;
    }

    /**
     * Start mocking HAL with given mock. Actual H/W will be stop functioning until mocking is
     * stopped. The call will be blocked until all pending events are delivered to upper layer.
     */
    public void startHalMocking(HalMock mock) {
        //TODO
    }

    public void stopHalMocking() {
        //TODO
    }

    public int subscribeProperty(HalProperty property, float samplingRateHz) {
        return subscribeProperty(mNativePtr, property.propertyType, samplingRateHz);
    }

    public void unsubscribeProperty(HalProperty property) {
        unsubscribeProperty(mNativePtr, property.propertyType);
    }

    public int getIntProperty(HalProperty property) throws IllegalStateException {
        assertDataType(property, HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_INT32);
        return getIntProperty(mNativePtr, property.propertyType);
    }

    public long getLongProperty(HalProperty property) throws IllegalStateException {
        assertDataType(property, HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_INT64);
        return getLongProperty(mNativePtr, property.propertyType);
    }

    public float getFloatProperty(HalProperty property) throws IllegalStateException {
        assertDataType(property, HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT);
        return getFloatProperty(mNativePtr, property.propertyType);
    }

    /**
     * Read String property.
     * @param property
     * @return
     * @throws IllegalStateException
     */
    public String getStringProperty(HalProperty property) throws IllegalStateException {
        assertDataType(property, HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_STRING);
        byte[] utf8String = getStringProperty(mNativePtr, property.propertyType);
        if (utf8String == null) {
            Log.e(CarLog.TAG_HAL, "get:HAL returned null for valid property 0x" +
                    Integer.toHexString(property.propertyType));
            return null;
        }
        try {
            String value = new String(utf8String, "UTF-8");
            return value;
        } catch (UnsupportedEncodingException e) {
            Log.e(CarLog.TAG_HAL, "cannot decode UTF-8", e);
        }
        return null;
    }

    /**
     * Notification from Hal layer for incoming Hal events. This version allows passing multiple
     * events in one call to reduce JNI overhead. Native side should combine multiple events if
     * possible to reduce overhead. The end result is more like simpler serialization but it should
     * not be as expensive as full serialization like protobuf and this should reduce JNI overhead
     * a lot.
     * @param numEvents Number of events passed this time.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    private void onHalDataEvents(int numEvents) {
        int propertyInfoIndex = 0;
        int intDataIndex = 0;
        int floatDataIndex = 0;
        for (int i = 0; i < numEvents; i++) {
            int property = mNativepPropertyInfos[propertyInfoIndex];
            int dataType = mNativepPropertyInfos[propertyInfoIndex + 1];
            int intLength = mNativepPropertyInfos[propertyInfoIndex + 2];
            int floatLength = mNativepPropertyInfos[propertyInfoIndex + 3];
            long timeStamp = mNativeTimestampsNs[i];
            propertyInfoIndex += 4;
            HalServiceBase service = mPropertyHandlers.get(property);
            if (service == null) {
                Log.e(CarLog.TAG_HAL, "No service for event:" + property);
                intDataIndex += intLength;
                floatDataIndex += floatLength;
                continue;
            }
            switch (dataType) {
                case HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN:
                    if (intLength != 1 && floatLength != 0) {
                        Log.e(CarLog.TAG_HAL, "Wrong data type");
                    } else {
                        boolean value = mNativeIntData[intDataIndex] == 1;
                        service.handleBooleanHalEvent(property, value, timeStamp);
                    }
                    break;
                case HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_INT32:
                    if (intLength != 1 && floatLength != 0) {
                        Log.e(CarLog.TAG_HAL, "Wrong data type");
                    } else {
                        int value = mNativeIntData[intDataIndex];
                        service.handleIntHalEvent(property, value, timeStamp);
                    }
                    break;
                case HalPropertyConst.VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT:
                    if (intLength != 0 && floatLength != 1) {
                        Log.e(CarLog.TAG_HAL, "Wrong data type");
                    } else {
                        float value = mNativeFloatData[floatDataIndex];
                        service.handleFloatHalEvent(property, value, timeStamp);
                    }
                    break;
                //TODO handle other types
                default:
                    Log.e(CarLog.TAG_HAL, "onHalEvents not implemented for type " + dataType);
                    break;
            }
            intDataIndex += intLength;
            floatDataIndex += floatLength;
        }
        //TODO add completion for sensor HAL to prevent additional blocking
    }

    /**
     * Init native part with passing arrays to use for
     * {@link #onHalDataEvents(int)}.
     * @param props
     * @param timestamps
     * @param dataTypes
     * @param intValues
     * @param floatValues
     * @return
     */
    private native long nativeInit(int[] propetyInfos, long[] timestamps, int[] intValues,
            float[] floatValues);
    private native void nativeRelease(long nativePtr);
    private native HalProperty[] getSupportedProperties(long nativePtr);
    private native void setIntProperty(long nativePtr, int property, int value);
    private native int getIntProperty(long nativePtr, int property) throws IllegalStateException;
    private native long getLongProperty(long nativePtr, int property) throws IllegalStateException;
    private native void setFloatProperty(long nativePtr, int property, float value);
    private native float getFloatProperty(long nativePtr, int property)
            throws IllegalStateException;
    /**
     * Reads UTF-8 string as byte array. Caller should convert it to String if necessary.
     * @param nativePtr
     * @param property
     * @return
     * @throws IllegalStateException
     */
    private native byte[] getStringProperty(long nativePtr, int property)
            throws IllegalStateException;

    /**
     * subsribe property.
     * @param nativePtr
     * @param propertyHandle
     * @param sampleRateHz
     * @return error code, 0 for ok.
     */
    private native int subscribeProperty(long nativePtr, int property, float sampleRateHz);
    private native void unsubscribeProperty(long nativePtr, int property);

    static void assertDataType(HalProperty prop, int expectedType) {
        if (prop.dataType != expectedType) {
            throw new RuntimeException("Property 0x" + Integer.toHexString(prop.propertyType) +
                    " with type " + prop.dataType);
        }
    }

    private class DefaultHandler extends Handler {
        private static final int MSG_INIT = 0;

        private DefaultHandler(Looper looper) {
            super(looper);
        }

        private void requestInit() {
            Message msg = obtainMessage(MSG_INIT);
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    doInit();
                    break;
                default:
                    Log.w(CarLog.TAG_HAL, "unown message:" + msg.what, new RuntimeException());
                    break;
            }
        }
    }

    public void dump(PrintWriter writer) {
        writer.println("**dump HAL services**");
        for (HalServiceBase service: mAllServices) {
            service.dump(writer);
        }
    }
}
