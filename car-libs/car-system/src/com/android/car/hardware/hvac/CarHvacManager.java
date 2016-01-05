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

package com.android.car.hardware.hvac;

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.support.car.Car;
import android.support.car.CarManagerBase;
import android.support.car.CarNotConnectedException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * API for controlling HVAC system in cars
 */
@SystemApi
public class CarHvacManager implements CarManagerBase {
    public final static boolean DBG = true;
    public final static String TAG = "CarHvacManager";

    /**
     * Define types of values that are available.  Boolean type will be overloaded as an int for
     * binder calls, and unpacked inside the HvacManager.
     */
    public static final int PROPERTY_TYPE_BOOLEAN = 0;
    public static final int PROPERTY_TYPE_FLOAT   = 1;
    public static final int PROPERTY_TYPE_INT     = 2;

    /**
     * Global HVAC properties.  There is only a single instance in a car.
     * Global properties are in the range of 0-0x3FFF.
     */
    /**
     * Mirror defrosters state, bool.
     */
    public static final int HVAC_MIRROR_DEFROSTER_ON     = 1;
    /**
     * Air conditioner state, bool
     */
    public static final int HVAC_AC_ON                   = 2;
    /**
     * HVAC is in automatic mode, bool.
     */
    public static final int HVAC_AUTOMATIC_MODE_ON       = 3;
    /**
     * Air recirculation is active, bool.
     */
    public static final int HVAC_AIR_RECIRCULATION_ON    = 4;
    /**
     * Steering wheel temp:  negative values indicate cooling, positive values indicate heat, int.
     */
    public static final int HVAC_STEERING_WHEEL_TEMP     = 5;

    /**
     * HVAC_ZONED_* represents properties available on a per-zone basis.  All zones in a car are
     * are not required to have the same properties.  Zone specific properties start at 0x4000 and
     * above.
     *
     * Temperature setpoint desired by the user, in terms of F or C, depending on TEMP_IS_FARENHEIT.
     * If temp is celsius, the format is 31.1 (i.e. LSB = 0.5C).  int.
     */
    public static final int HVAC_ZONED_TEMP_SETPOINT             = 0x4001;
    /**
     * Actual zone temperature is read only integer, in terms of F or C, int.
     */
    public static final int HVAC_ZONED_TEMP_ACTUAL               = 0x4002;
    /**
     * Temperature is in degrees farenheit if this is true, bool.
     */
    public static final int HVAC_ZONED_TEMP_IS_FARENHEIT         = 0x4003;
    /**
     * Fan speed setpoint is an integer from 0-n, depending on the number of fan speeds available.
     * Selection determines the fan position, int.
     */
    public static final int HVAC_ZONED_FAN_SPEED_SETPOINT        = 0x4004;
    /**
     * Actual fan speed is a read-only value, expressed in RPM, int.
     */
    public static final int HVAC_ZONED_FAN_SPEED_RPM             = 0x4005;
    /**
     * Fan position available is a bitmask of positions available for each zone, int.
     */
    public static final int HVAC_ZONED_FAN_POSITION_AVAILABLE    = 0x4006;
    /**
     * Current fan position setting, int.
     */
    public static final int HVAC_ZONED_FAN_POSITION              = 0x4007;
    /**
     * Seat temperature is negative for cooling, positive for heating.  Temperature is a setting,
     * i.e. -3 to 3 for 3 levels of cooling and 3 levels of heating.  int.
     */
    public static final int HVAC_ZONED_SEAT_TEMP                 = 0x4008;
    /**
     * Defroster is based off of window position
     */
    public static final int HVAC_WINDOW_DEFROSTER_ON             = 0x5001;

    // Minimum supported version of the service.
    private static final int MIN_SUPPORTED_VERSION = 1;

    // Minimum supported version of the callback.
    private static final int MIN_SUPPORTED_CALLBACK_VERSION = 1;

    // Constants handled in the handler (see mHandler below).
    private final static int MSG_HVAC_EVENT = 0;

    public static class CarHvacBaseProperty {
        protected final int mPropertyId;
        protected final int mType;
        protected final int mZone;

        public CarHvacBaseProperty(int propId, int type, int zone) {
            mPropertyId = propId;
            mType       = type;
            mZone       = zone;
        }

        public int getPropertyId() { return mPropertyId; }
        public int getType()       { return mType; }
        public int getZone()       { return mZone; }
    }

    public static final class CarHvacBooleanProperty extends CarHvacBaseProperty {
        public CarHvacBooleanProperty(int propId, int zone) {
            super(propId, PROPERTY_TYPE_BOOLEAN, zone);
        }
    }

    public static final class CarHvacFloatProperty extends CarHvacBaseProperty {
        private float mMaxValue;
        private float mMinValue;

        public CarHvacFloatProperty(int propId, int zone, float max, float min) {
            super(propId, PROPERTY_TYPE_FLOAT, zone);
            mMaxValue      = max;
            mMinValue      = min;
        }

        public float getMaxValue()      { return mMaxValue; }
        public float getMinValue()      { return mMinValue; }
    }

    public static final class CarHvacIntProperty extends CarHvacBaseProperty {
        private int mMaxValue;
        private int mMinValue;

        public CarHvacIntProperty(int propId, int zone, int max, int min) {
            super(propId, PROPERTY_TYPE_INT, zone);
            mMaxValue      = max;
            mMinValue      = min;
        }

        public int getMaxValue()      { return mMaxValue; }
        public int getMinValue()      { return mMinValue; }
    }

    public static final class CarHvacBooleanValue extends CarHvacBaseProperty {
        private boolean mValue;

        public CarHvacBooleanValue(int propId, int zone, boolean value) {
            super(propId, PROPERTY_TYPE_BOOLEAN, zone);
            mValue = value;
        }

        public boolean getValue() { return mValue; }
    }


    public static final class CarHvacFloatValue extends CarHvacBaseProperty {
        private float mValue;

        public CarHvacFloatValue(int propId, int zone, float value) {
            super(propId, PROPERTY_TYPE_FLOAT, zone);
            mValue = value;
        }

        public float getValue() { return mValue; }
    }

    public static final class CarHvacIntValue extends CarHvacBaseProperty {
        private int mValue;

        public CarHvacIntValue(int propId, int zone, int value) {
            super(propId, PROPERTY_TYPE_INT, zone);
            mValue = value;
        }

        public int getValue() { return mValue; }
    }

    public interface CarHvacEventListener {
        // Called when an HVAC property is updated
        void onChangeEvent(final CarHvacBaseProperty value);

        // Called when an error is detected with a property
        void onErrorEvent(final int propertyId, final int zone);
    }

    private final ICarHvac mService;
    private final Object mLock = new Object();
    private CarHvacEventListener mListener = null;
    private CarHvacEventListenerToService mListenerToService = null;
    private int mServiceVersion;
    private static final class EventCallbackHandler extends Handler {
        WeakReference<CarHvacManager> mMgr;

        EventCallbackHandler(CarHvacManager mgr, Looper looper) {
            super(looper);
            mMgr = new WeakReference<CarHvacManager>(mgr);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HVAC_EVENT:
                    CarHvacManager mgr = mMgr.get();
                    if (mgr != null) {
                        mgr.dispatchEventToClient((CarHvacEvent) msg.obj);
                    }
                    break;
                default:
                    Log.e(TAG, "Event type not handled?" + msg);
                    break;
            }
        }
    }

    private final Handler mHandler;

    private static class CarHvacEventListenerToService extends ICarHvacEventListener.Stub {
        private final WeakReference<CarHvacManager> mManager;

        public CarHvacEventListenerToService(CarHvacManager manager) {
            mManager = new WeakReference<CarHvacManager>(manager);
        }

        @Override
        public void onEvent(CarHvacEvent event) {
            CarHvacManager manager = mManager.get();
            if (manager != null) {
                manager.handleEvent(event);
            }
        }
    }

    /**
     * Get an instance of the CarHvacManager.
     *
     * Should not be obtained directly by clients, use {@link Car.getCarManager()} instead.
     * @hide
     */
    public CarHvacManager(Context context, ICarHvac service, Looper looper) {
        mService = service;
        mHandler = new EventCallbackHandler(this, looper);
        mServiceVersion = getVersion();
        if (mServiceVersion < MIN_SUPPORTED_VERSION) {
            Log.w(TAG, "Old service version: " + mServiceVersion +
                " for client lib: " + MIN_SUPPORTED_VERSION);
        }
    }

    private int getVersion() {
        try {
            return mService.getVersion();
        } catch (RemoteException e) {
            Log.w(TAG, "Exception in getVersion", e);
        }
        return 1;
    }

    /**
     * Register {@link CarHvacEventListener} to get HVAC property changes
     *
     * @param listener Implements onEvent() for property change updates
     * @return
     */
    public synchronized void registerListener(CarHvacEventListener listener)
            throws CarNotConnectedException {
        if (mListener != null) {
            throw new IllegalStateException("Listner already registered. Did you call " +
                "registerListener() twice?");
        }

        mListener = listener;
        try {
            mListenerToService = new CarHvacEventListenerToService(this);
            mService.registerListener(mListenerToService, MIN_SUPPORTED_CALLBACK_VERSION);
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not connect: " + ex.toString());
            throw new CarNotConnectedException(ex);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
        }
    }

    /**
     * Unregister {@link CarHvacEventListener}.
     *
     * @param
     * @return
     */
    public synchronized void unregisterListener() {
        if (DBG) {
            Log.d(TAG, "unregisterListener");
        }
        try {
            mService.unregisterListener(mListenerToService);
        } catch (RemoteException ex) {
            // do nothing.
            Log.e(TAG, "Could not connect: " + ex.toString());
        }
        mListenerToService = null;
        mListener = null;
    }

    /**
     * Returns the list of HVAC properties available.
     *
     * @return Caller must check the property type and typecast to the appropriate subclass
     * (CarHvacBooleanProperty, CarHvacFloatProperty, CarrHvacIntProperty)
     */
    public List<CarHvacBaseProperty> getPropertyList() {
        List<CarHvacBaseProperty> hvacProps = new ArrayList<CarHvacBaseProperty>();
        List<CarHvacProperty> carProps = null;
        try {
            carProps = mService.getHvacProperties();
        } catch (RemoteException e) {
            Log.w(TAG, "Exception in getPropertyList", e);
            return null;
        }

        for (CarHvacProperty carProp : carProps) {
            switch (carProp.getType()) {
                case PROPERTY_TYPE_BOOLEAN: {
                    CarHvacBooleanProperty newProp =
                            new CarHvacBooleanProperty(carProp.getPropertyId(), carProp.getZone());
                    hvacProps.add(newProp);
                } break;
                case PROPERTY_TYPE_FLOAT: {
                    CarHvacFloatProperty newProp =
                            new CarHvacFloatProperty(carProp.getPropertyId(), carProp.getZone(),
                                    carProp.getFloatMax(), carProp.getFloatMin());
                    hvacProps.add(newProp);
                } break;
                case PROPERTY_TYPE_INT: {
                    CarHvacIntProperty newProp =
                            new CarHvacIntProperty(carProp.getPropertyId(), carProp.getZone(),
                                    carProp.getIntMax(), carProp.getIntMin());
                    hvacProps.add(newProp);
                } break;
            }
        }
        return hvacProps;
    }

    /**
     * Returns value of a bool property
     *
     * @param prop Property ID to get
     * @param zone Zone of the property to get
     * @return
     */
    public boolean getBooleanProperty(int prop, int zone) {
        CarHvacProperty carProp = null;
        if (DBG) {
            Log.d(TAG, "getBooleanProperty:  prop = " + prop + " zone = " + zone);
        }
        try {
            carProp = mService.getProperty(prop, zone);
        } catch (RemoteException ex) {
            Log.e(TAG, "getProperty failed with " + ex.toString());
            return false;
        }

        if (carProp.getType() == PROPERTY_TYPE_BOOLEAN) {
            return carProp.getIntValue() == 1;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Returns value of a float property
     *
     * @param prop Property ID to get
     * @param zone Zone of the property to get
     * @return
     */
    public float getFloatProperty(int prop, int zone) {
        CarHvacProperty carProp = null;
        if (DBG) {
            Log.d(TAG, "getFloatProperty:  prop = " + prop + " zone = " + zone);
        }
        try {
            carProp = mService.getProperty(prop, zone);
        } catch (RemoteException ex) {
            Log.e(TAG, "getProperty failed with " + ex.toString());
            return 0;
        }

        if (carProp.getType() == PROPERTY_TYPE_FLOAT) {
            return carProp.getFloatValue();
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Returns value of a integer property
     *
     * @param prop Property ID to get
     * @param zone Zone of the property to get
     * @return
     */
    public int getIntProperty(int prop, int zone) {
        CarHvacProperty carProp = null;
        if (DBG) {
            Log.d(TAG, "getIntProperty:  prop = " + prop + " zone = " + zone);
        }
        try {
            carProp = mService.getProperty(prop, zone);
        } catch (RemoteException ex) {
            Log.e(TAG, "getProperty failed with " + ex.toString());
            return 0;
        }

        if (carProp.getType() == PROPERTY_TYPE_INT) {
            return carProp.getIntValue();
        } else {
            throw new IllegalArgumentException();
        }
    }


    /**
     * Modifies a property.  If the property modification doesn't occur, an error event shall be
     * generated and propagated back to the application.
     *
     * @param prop Property ID to modify
     * @param zone Zone(s) to apply the modification.  Multiple zones may be OR'd together
     * @param val Value to set
     */
    public void setBooleanProperty(int prop, int zone, boolean val) {
        if (DBG) {
            Log.d(TAG, "setBooleanProperty:  prop = " + prop + " zone = " + zone + " val = " + val);
        }
        try {
            CarHvacProperty carProp = new CarHvacProperty(prop, zone, val);
            mService.setProperty(carProp);
        } catch (RemoteException ex) {
            Log.e(TAG, "setBooleanProperty failed with " + ex.toString());
        }
    }

    public void setFloatProperty(int prop, int zone, float val) {
        if (DBG) {
            Log.d(TAG, "setFloatProperty:  prop = " + prop + " zone = " + zone + " val = " + val);
        }
        try {
            // Set floatMin and floatMax to 0, as they are ignored in set()
            CarHvacProperty carProp = new CarHvacProperty(prop, zone, 0, 0, val);
            mService.setProperty(carProp);
        } catch (RemoteException ex) {
            Log.e(TAG, "setFloatProperty failed with " + ex.toString());
        }
    }

    public void setIntProperty(int prop, int zone, int val) {
        if (DBG) {
            Log.d(TAG, "setIntProperty:  prop = " + prop + " zone = " + zone + " val = " + val);
        }
        try {
            // Set floatMin and floatMax to 0, as they are ignored in set()
            CarHvacProperty carProp = new CarHvacProperty(prop, zone, 0, 0, val);
            mService.setProperty(carProp);
        } catch (RemoteException ex) {
            Log.e(TAG, "setIntProperty failed with " + ex.toString());
        }
    }

    private void dispatchEventToClient(CarHvacEvent event) {
        CarHvacEventListener listener = null;
        synchronized (this) {
            listener = mListener;
        }
        if (listener != null) {
            int propertyId = event.getPropertyId();
            int zone = event.getZone();

            switch(event.getEventType()) {
                case CarHvacEvent.HVAC_EVENT_PROPERTY_CHANGE:
                    CarHvacBaseProperty value = null;
                    switch(event.getPropertyType()) {
                        case PROPERTY_TYPE_BOOLEAN: {
                            value = new CarHvacBooleanValue(propertyId, zone,
                                    event.getIntValue() == 1);
                            break;
                        }
                        case PROPERTY_TYPE_FLOAT: {
                            value = new CarHvacFloatValue(propertyId, zone, event.getFloatValue());
                            break;
                        }
                        case PROPERTY_TYPE_INT: {
                            value = new CarHvacIntValue(propertyId, zone, event.getIntValue());
                            break;
                        }
                        default:
                            throw new IllegalArgumentException();
                    }
                    listener.onChangeEvent(value);
                    break;
                case CarHvacEvent.HVAC_EVENT_ERROR:
                    listener.onErrorEvent(propertyId, zone);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        } else {
            Log.e(TAG, "Listener died, not dispatching event.");
        }
    }

    private void handleEvent(CarHvacEvent event) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_HVAC_EVENT, event));
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        mListener = null;
        mListenerToService = null;
    }
}

