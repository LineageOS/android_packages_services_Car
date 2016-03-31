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

import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.car.VehicleZoneUtil;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * API for controlling HVAC system in cars
 * @hide
 */
@SystemApi
public class CarHvacManager implements CarManagerBase {
    public final static boolean DBG = true;
    public final static String TAG = "CarHvacManager";

    /**
     * Define types of values that are available.  Boolean type will be overloaded as an int for
     * binder calls, and unpacked inside the HvacManager.
     */
    public static final int PROPERTY_TYPE_BOOLEAN      = 0;
    public static final int PROPERTY_TYPE_FLOAT        = 1;
    public static final int PROPERTY_TYPE_INT          = 2;
    public static final int PROPERTY_TYPE_INT_VECTOR   = 3;
    public static final int PROPERTY_TYPE_FLOAT_VECTOR = 4;

    /**
     * Global HVAC properties.  There is only a single instance in a car.
     * Global properties are in the range of 0-0x3FFF.
     */
    /**
     * Mirror defrosters state, bool.
     */
    public static final int HVAC_MIRROR_DEFROSTER_ON     = 0x0001;
    /**
     * HVAC is in automatic mode, bool.
     */
    public static final int HVAC_AUTOMATIC_MODE_ON       = 0x0003;
    /**
     * Air recirculation is active, bool.
     */
    public static final int HVAC_AIR_RECIRCULATION_ON    = 0x0004;
    /**
     * Steering wheel temp:  negative values indicate cooling, positive values indicate heat, int.
     */
    public static final int HVAC_STEERING_WHEEL_TEMP     = 0x0005;

    /**
     * The maximum id that can be assigned to global (non-zoned) property.
     */
    public static final int MAX_GLOBAL_PROPERTY_ID       = 0x3fff;

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
     * Temperature is in degrees fahrenheit if this is true, bool.
     */
    public static final int HVAC_ZONED_TEMP_IS_FAHRENHEIT        = 0x4003;
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
     * Air conditioner state, bool
     */
    public static final int HVAC_ZONED_AC_ON                   = 0x4009;
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
        protected final int mZones;

        public CarHvacBaseProperty(int propId, int type, int zones) {
            mPropertyId = propId;
            mType       = type;
            mZones       = zones;
        }

        public int getPropertyId() {
            return mPropertyId;
        }

        public int getType() {
            return mType;
        }

        /**
         * Tells if the given property is zoned property or global property
         */
        public boolean isZonedProperty() {
            return mPropertyId > MAX_GLOBAL_PROPERTY_ID;
        }

        /**
         * Return bit flags of supported zones.
         */
        public int getZones()       { return mZones; }

        /**
         * Return an active zone for Hvac event. This will return only one zone.
         * If there is no valid zone, this will return 0.
         */
        public int getZone() {
            if (mZones == 0) {
                return 0;
            }
            int flag = 0x1;
            for (int i = 0; i < 32; i++) {
                if ((flag & mZones) != 0) {
                    return flag;
                }
                flag <<= 1;
            }
            return 0;
        }

        @Override
        public String toString() {
            return "CarHvacBaseProperty [mPropertyId=0x" + Integer.toHexString(mPropertyId) +
                    ", mType=0x" + Integer.toHexString(mType) +
                    ", mZones=0x" + Integer.toHexString(mZones) + "]";
        }

        protected void assertZonedProperty() {
            if (!isZonedProperty()) {
                throw new IllegalArgumentException(
                        "assertZonedProperty called for non-zoned property 0x" +
                                Integer.toHexString(mPropertyId));
            }
        }

        protected void assertNonZonedProperty() {
            if (isZonedProperty()) {
                throw new IllegalArgumentException(
                        "assertNonZonedProperty called for zoned property 0x" +
                        Integer.toHexString(mPropertyId));
            }
        }
    }

    public static final class CarHvacBooleanProperty extends CarHvacBaseProperty {
        public CarHvacBooleanProperty(int propId, int zone) {
            super(propId, PROPERTY_TYPE_BOOLEAN, zone);
        }
    }

    public static final class CarHvacFloatProperty extends CarHvacBaseProperty {
        private final float[] mMaxValues;
        private final float[] mMinValues;

        public CarHvacFloatProperty(int propId, int zones, float[] maxs, float mins[]) {
            super(propId, PROPERTY_TYPE_FLOAT, zones);
            int expectedLength = zones == 0 ? 1 : VehicleZoneUtil.getNumberOfZones(zones);
            if (maxs.length != expectedLength || mins.length != expectedLength) {
                throw new IllegalArgumentException("Expected length:" + expectedLength +
                        " while maxs length:" + maxs.length + " mins length:" + mins.length +
                        " property:0x" + Integer.toHexString(propId));
            }
            mMaxValues      = maxs;
            mMinValues      = mins;
        }

        /**
         * Get max value. Should be used only for non-zoned property.
         */
        public float getMaxValue() {
            assertNonZonedProperty();
            return mMaxValues[0];
        }

        /**
         * Get min value. Should be used only for non-zoned property.
         */
        public float getMinValue() {
            assertNonZonedProperty();
            return mMinValues[0];
        }

        public float getMaxValue(int zone) {
            assertZonedProperty();
            return mMaxValues[VehicleZoneUtil.zoneToIndex(mZones, zone)];
        }

        public float getMinValue(int zone) {
            assertZonedProperty();
            return mMinValues[VehicleZoneUtil.zoneToIndex(mZones, zone)];
        }

        @Override
        public String toString() {
            return "CarHvacFloatProperty [mMaxValues=" + Arrays.toString(mMaxValues)
                    + ", mMinValues=" + Arrays.toString(mMinValues) + " " + super.toString() + "]";
        }
    }

    public static final class CarHvacIntProperty extends CarHvacBaseProperty {
        private int[] mMaxValues;
        private int[] mMinValues;

        public CarHvacIntProperty(int propId, int zones, int[] maxs, int[] mins) {
            super(propId, PROPERTY_TYPE_INT, zones);
            int expectedLength = zones == 0 ? 1 : VehicleZoneUtil.getNumberOfZones(zones);
            if (maxs.length != expectedLength || mins.length != expectedLength) {
                throw new IllegalArgumentException("Expected length:" + expectedLength +
                        " while maxs length:" + maxs.length + " mins length:" + mins.length +
                        " property:0x" + Integer.toHexString(propId));
            }
            mMaxValues      = maxs;
            mMinValues      = mins;
        }

        /**
         * Get max value. Should be used only for non-zoned property.
         */
        public int getMaxValue() {
            assertNonZonedProperty();
            return mMaxValues[0];
        }

        /**
         * Get min value. Should be used only for non-zoned property.
         */
        public int getMinValue() {
            assertNonZonedProperty();
            return mMinValues[0];
        }

        public int getMaxValue(int zone) {
            assertZonedProperty();
            return mMaxValues[VehicleZoneUtil.zoneToIndex(mZones, zone)];
        }

        public int getMinValue(int zone) {
            assertZonedProperty();
            return mMinValues[VehicleZoneUtil.zoneToIndex(mZones, zone)];
        }

        @Override
        public String toString() {
            return "CarHvacIntProperty [mMaxValues=" + Arrays.toString(mMaxValues)
                    + ", mMinValues=" + Arrays.toString(mMinValues) + " " + super.toString() + "]";
        }
    }

    public static final class CarHvacBooleanValue extends CarHvacBaseProperty {
        private boolean mValue;

        public CarHvacBooleanValue(int propId, int zones, boolean value) {
            super(propId, PROPERTY_TYPE_BOOLEAN, zones);
            mValue = value;
        }

        public boolean getValue() { return mValue; }

        @Override
        public String toString() {
            return "CarHvacBooleanValue [mValue=" + mValue + " " + super.toString() + "]";
        }
    }


    public static final class CarHvacFloatValue extends CarHvacBaseProperty {
        private float mValue;

        public CarHvacFloatValue(int propId, int zones, float value) {
            super(propId, PROPERTY_TYPE_FLOAT, zones);
            mValue = value;
        }

        public float getValue() { return mValue; }

        @Override
        public String toString() {
            return "CarHvacFloatValue [mValue=" + mValue + " " + super.toString() + "]";
        }
    }

    public static final class CarHvacIntValue extends CarHvacBaseProperty {
        private int mValue;

        public CarHvacIntValue(int propId, int zones, int value) {
            super(propId, PROPERTY_TYPE_INT, zones);
            mValue = value;
        }

        public int getValue() { return mValue; }

        @Override
        public String toString() {
            return "CarHvacIntValue [mValue=" + mValue + " " + super.toString() + "]";
        }
    }

    public interface CarHvacEventListener {
        // Called when an HVAC property is updated
        void onChangeEvent(final CarHvacBaseProperty value);

        // Called when an error is detected with a property
        void onErrorEvent(final int propertyId, final int zone);
    }

    private final ICarHvac mService;
    private CarHvacEventListener mListener = null;
    private CarHvacEventListenerToService mListenerToService = null;

    private static final class EventCallbackHandler extends Handler {
        WeakReference<CarHvacManager> mMgr;

        EventCallbackHandler(CarHvacManager mgr, Looper looper) {
            super(looper);
            mMgr = new WeakReference<>(mgr);
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
            mManager = new WeakReference<>(manager);
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
    public CarHvacManager(IBinder service, Context context, Looper looper) {
        mService = ICarHvac.Stub.asInterface(service);
        mHandler = new EventCallbackHandler(this, looper);
    }

    public static boolean isZonedProperty(int propertyId) {
        return propertyId > MAX_GLOBAL_PROPERTY_ID;
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
            throw new IllegalStateException("Listener already registered. Did you call " +
                "registerListener() twice?");
        }

        mListener = listener;
        try {
            mListenerToService = new CarHvacEventListenerToService(this);
            mService.registerListener(mListenerToService);
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not connect: " + ex.toString());
            mListener = null;
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
            Log.e(TAG, "Could not unregister: " + ex.toString());
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
        List<CarHvacBaseProperty> hvacProps = new ArrayList<>();
        List<CarHvacProperty> carProps;
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
                            new CarHvacBooleanProperty(carProp.getPropertyId(), carProp.getZones());
                    hvacProps.add(newProp);
                } break;
                case PROPERTY_TYPE_FLOAT: {
                    CarHvacFloatProperty newProp =
                            new CarHvacFloatProperty(carProp.getPropertyId(), carProp.getZones(),
                                    carProp.getFloatMaxs(), carProp.getFloatMins());
                    hvacProps.add(newProp);
                } break;
                case PROPERTY_TYPE_INT: {
                    CarHvacIntProperty newProp =
                            new CarHvacIntProperty(carProp.getPropertyId(), carProp.getZones(),
                                    carProp.getIntMaxs(), carProp.getIntMins());
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
        CarHvacProperty carProp;
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
            return carProp.getBooleanValue();
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
        CarHvacProperty carProp;
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
        CarHvacProperty carProp;
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
            CarHvacProperty carProp = new CarHvacProperty(prop, zone,
                    new float[] { 0 }, new float[] { 0 }, val);
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
            // Set intMin and intMax to 0, as they are ignored in set()
            CarHvacProperty carProp = new CarHvacProperty(prop, zone,
                    new int[] { 0 }, new int[] { 0 }, val);
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
        if (mListener != null) {
            unregisterListener();
        }
    }
}
