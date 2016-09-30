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

package android.car.hardware.hvac;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManagerBase;
import android.car.hardware.property.CarPropertyManagerBase.CarPropertyEventListener;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.ArraySet;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;

/**
 * API for controlling HVAC system in cars
 * @hide
 */
@SystemApi
public class CarHvacManager implements CarManagerBase {
    private final static boolean DBG = false;
    private final static String TAG = "CarHvacManager";
    private final CarPropertyManagerBase mMgr;
    private final ArraySet<CarHvacEventListener> mListeners = new ArraySet<>();
    private CarPropertyEventListenerToBase mListenerToBase = null;

    /**
     * HVAC property IDs for get/set methods
     */
    @IntDef({
            HvacPropertyId.MIRROR_DEFROSTER_ON,
            HvacPropertyId.STEERING_WHEEL_TEMP,
            HvacPropertyId.OUTSIDE_AIR_TEMP,
            HvacPropertyId.TEMPERATURE_UNITS,
            HvacPropertyId.MAX_GLOBAL_PROPERTY_ID,
            HvacPropertyId.ZONED_TEMP_SETPOINT,
            HvacPropertyId.ZONED_TEMP_ACTUAL,
            HvacPropertyId.ZONED_FAN_SPEED_SETPOINT,
            HvacPropertyId.ZONED_FAN_SPEED_RPM,
            HvacPropertyId.ZONED_FAN_POSITION_AVAILABLE,
            HvacPropertyId.ZONED_FAN_POSITION,
            HvacPropertyId.ZONED_SEAT_TEMP,
            HvacPropertyId.ZONED_AC_ON,
            HvacPropertyId.ZONED_AUTOMATIC_MODE_ON,
            HvacPropertyId.ZONED_AIR_RECIRCULATION_ON,
            HvacPropertyId.ZONED_MAX_AC_ON,
            HvacPropertyId.ZONED_DUAL_ZONE_ON,
            HvacPropertyId.ZONED_MAX_DEFROST_ON,
            HvacPropertyId.ZONED_HVAC_POWER_ON,
            HvacPropertyId.WINDOW_DEFROSTER_ON,
    })
    public @interface HvacPropertyId {
        /**
         * Global HVAC properties.  There is only a single instance in a car.
         * Global properties are in the range of 0-0x3FFF.
         */
        /** Mirror defrosters state, bool. */
        int MIRROR_DEFROSTER_ON = 0x0001;
        /** Steering wheel temp:  negative values indicate cooling, positive values indicate
         * heat, int. */
        int STEERING_WHEEL_TEMP = 0x0002;
        /** Outside air temperature, float. */
        int OUTSIDE_AIR_TEMP = 0x0003;
        /** Temperature units being used, int
         *  0x30 = Celsius
         *  0x31 = Fahrenheit
         */
        int TEMPERATURE_UNITS = 0x0004;


        /** The maximum id that can be assigned to global (non-zoned) property. */
        int MAX_GLOBAL_PROPERTY_ID = 0x3fff;

        /**
         * ZONED_* represents properties available on a per-zone basis.  All zones in a car are
         * not required to have the same properties.  Zone specific properties start at 0x4000.
         */
        /** Temperature setpoint desired by the user, int
         *  Temperature units are determined by TEMPERTURE_UNITS property
         */
        int ZONED_TEMP_SETPOINT = 0x4001;
        /** Actual zone temperature is read only integer, in terms of F or C, int. */
        int ZONED_TEMP_ACTUAL = 0x4002;
        /** HVAC system powered on / off, bool
         *  In many vehicles, if the HVAC system is powered off, the SET and GET command will
         *  throw an IllegalStateException.  To correct this, need to turn on the HVAC module first
         *  before manipulating a parameter.
         */
        int ZONED_HVAC_POWER_ON = 0x4003;
        /** Fan speed setpoint is an integer from 0-n, depending on the number of fan speeds
         * available. Selection determines the fan position, int. */
        int ZONED_FAN_SPEED_SETPOINT = 0x4004;
        /** Actual fan speed is a read-only value, expressed in RPM, int. */
        int ZONED_FAN_SPEED_RPM = 0x4005;
        /** Fan position available is a bitmask of positions available for each zone, int. */
        int ZONED_FAN_POSITION_AVAILABLE = 0x4006;
        /** Current fan position setting, int. */
        int ZONED_FAN_POSITION = 0x4007;
        /** Seat temperature is negative for cooling, positive for heating.  Temperature is a
         * setting, i.e. -3 to 3 for 3 levels of cooling and 3 levels of heating.  int. */
        int ZONED_SEAT_TEMP = 0x4008;
        /** Air conditioner state, bool */
        int ZONED_AC_ON = 0x4009;
        /** HVAC is in automatic mode, bool. */
        int ZONED_AUTOMATIC_MODE_ON = 0x400A;
        /** Air recirculation is active, bool. */
        int ZONED_AIR_RECIRCULATION_ON = 0x400B;
        /** Max AC is active, bool. */
        int ZONED_MAX_AC_ON = 0x400C;
        /** Dual zone is enabled, bool. */
        int ZONED_DUAL_ZONE_ON = 0x400D;
        /** Max Defrost is active, bool. */
        int ZONED_MAX_DEFROST_ON = 0x400E;
        /** Defroster is based off of window position, bool */
        int WINDOW_DEFROSTER_ON = 0x5001;
    }

    public interface CarHvacEventListener {
        /** Called when a property is updated */
        void onChangeEvent(CarPropertyValue value);

        /** Called when an error is detected with a property */
        void onErrorEvent(int propertyId, int zone);
    }

    private static class CarPropertyEventListenerToBase implements CarPropertyEventListener {
        private final WeakReference<CarHvacManager> mManager;

        public CarPropertyEventListenerToBase(CarHvacManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onChangeEvent(CarPropertyValue value) {
            CarHvacManager manager = mManager.get();
            if (manager != null) {
                manager.handleOnChangeEvent(value);
            }
        }

        @Override
        public void onErrorEvent(int propertyId, int zone) {
            CarHvacManager manager = mManager.get();
            if (manager != null) {
                manager.handleOnErrorEvent(propertyId, zone);
            }
        }
    }

    void handleOnChangeEvent(CarPropertyValue value) {
        Collection<CarHvacEventListener> listeners;
        synchronized (this) {
            listeners = new ArraySet<>(mListeners);
        }
        if (!listeners.isEmpty()) {
            for (CarHvacEventListener l: listeners) {
                l.onChangeEvent(value);
            }
        }
    }

    void handleOnErrorEvent(int propertyId, int zone) {
        Collection<CarHvacEventListener> listeners;
        synchronized (this) {
            listeners = new ArraySet<>(mListeners);
        }
        if (!listeners.isEmpty()) {
            for (CarHvacEventListener l: listeners) {
                l.onErrorEvent(propertyId, zone);
            }
        }
    }

    /**
     * Get an instance of the CarHvacManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @hide
     */
    public CarHvacManager(IBinder service, Context context, Handler handler) {
        mMgr = new CarPropertyManagerBase(service, handler, DBG, TAG);
    }

    /** Returns true if the property is a zoned type. */
    public static boolean isZonedProperty(int propertyId) {
        return propertyId > HvacPropertyId.MAX_GLOBAL_PROPERTY_ID;
    }

    /** Implement wrappers for contained CarPropertyManagerBase object */
    public synchronized void registerListener(CarHvacEventListener listener) throws
            CarNotConnectedException {
        if (mListeners.isEmpty()) {
            mListenerToBase = new CarPropertyEventListenerToBase(this);
            mMgr.registerListener(mListenerToBase);
        }
        mListeners.add(listener);
    }

    public synchronized void unregisterListener(CarHvacEventListener listener) throws
            CarNotConnectedException {
        mListeners.remove(listener);
        if (mListeners.isEmpty()) {
            mMgr.unregisterListener();
            mListenerToBase = null;
        }
    }

    public List<CarPropertyConfig> getPropertyList() throws CarNotConnectedException {
        return mMgr.getPropertyList();
    }

    public boolean getBooleanProperty(int prop, int area) throws CarNotConnectedException {
        return mMgr.getBooleanProperty(prop, area);
    }

    public float getFloatProperty(int prop, int area) throws CarNotConnectedException {
        return mMgr.getFloatProperty(prop, area);
    }

    public int getIntProperty(int prop, int area) throws CarNotConnectedException {
        return mMgr.getIntProperty(prop, area);
    }

    public void setBooleanProperty(int prop, int area, boolean val)
            throws CarNotConnectedException {
        mMgr.setBooleanProperty(prop, area, val);
    }

    public void setFloatProperty(int prop, int area, float val) throws CarNotConnectedException {
        mMgr.setFloatProperty(prop, area, val);
    }

    public void setIntProperty(int prop, int area, int val) throws CarNotConnectedException {
        mMgr.setIntProperty(prop, area, val);
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        mMgr.onCarDisconnected();
    }
}
