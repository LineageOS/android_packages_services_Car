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

package android.car.hardware.cabin;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManagerBase;
import android.car.hardware.property.CarPropertyManagerBase.CarPropertyEventCallback;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;

/**
 * API for controlling Cabin system in cars,
 * Most Car Cabin properties have both a MOVE and POSITION parameter associated with them.
 *
 * The MOVE parameter will start moving the device in the indicated direction.  Magnitude
 * indicates relative speed.  For instance, setting the WINDOW_MOVE parameter to +1 rolls
 * the window up.  Setting it to +2 (if available) will roll it up faster.
 *
 * POSITION parameter will move the device to the desired position.  For instance, if the
 * WINDOW_POS has a range of 0-100, setting this parameter to 50 will open the window
 * halfway.  Depending upon the initial position, the window may move up or down to the
 * 50% value.
 *
 * One or both of the MOVE/POSITION parameters may be implemented depending upon the
 * capability of the hardware.
 * @hide
 */
@SystemApi
public final class CarCabinManager implements CarManagerBase {
    private final static boolean DBG = false;
    private final static String TAG = "CarCabinManager";
    private final CarPropertyManagerBase mMgr;
    private final ArraySet<CarCabinEventCallback> mCallbacks = new ArraySet<>();
    private CarPropertyEventListenerToBase mListenerToBase = null;

    /** Door properties are zoned by VehicleDoor */
    /**
     * door position, int
     * This is an integer in case a door may be set to a particular position.
     * Max value indicates fully open, min value (0) indicates fully closed.
     *
     * Some vehicles (minivans) can open the door electronically.  Hence, the ability
     * to write this property.
     *
     */
    public static final int ID_DOOR_POS = 0x0001;
    /** door move, int */
    public static final int ID_DOOR_MOVE = 0x0002;
    /** door lock, bool
     * 'true' indicates door is locked
     */
    public static final int ID_DOOR_LOCK = 0x0003;

    /** Mirror properties are zoned by VehicleMirror */
    /**
     * mirror z position, int
     * Positive value indicates tilt upwards, negative value is downwards
     */
    public static final int ID_MIRROR_Z_POS = 0x1001;
    /** mirror z move, int */
    public static final int ID_MIRROR_Z_MOVE = 0x1002;
    /**
     * mirror y position, int
     * Positive value indicate tilt right, negative value is left
     */
    public static final int ID_MIRROR_Y_POS = 0x1003;
    /** mirror y move, int */
    public static final int ID_MIRROR_Y_MOVE = 0x1004;
    /**
     * mirror lock, bool
     * True indicates mirror positions are locked and not changeable
     */
    public static final int ID_MIRROR_LOCK = 0x1005;
    /**
     * mirror fold, bool
     * True indicates mirrors are folded
     */
    public static final int ID_MIRROR_FOLD = 0x1006;

    /** Seat properties are zoned by VehicleSeat */
    /**
     * seat memory select, int
     * This parameter selects the memory preset to use to select the seat position.
     * The minValue is always 0, and the maxValue determines the number of seat
     * positions available.
     *
     * For instance, if the driver's seat has 3 memory presets, the maxValue will be 3.
     * When the user wants to select a preset, the desired preset number (1, 2, or 3)
     * is set.
     */
    public static final int ID_SEAT_MEMORY_SELECT = 0x2001;
    /**
     * seat memory set, int
     * This setting allows the user to save the current seat position settings into
     * the selected preset slot.  The maxValue for each seat position shall match
     * the maxValue for VEHICLE_PROPERTY_SEAT_MEMORY_SELECT.
     */
    public static final int ID_SEAT_MEMORY_SET = 0x2002;
    /**
     * seat belt buckled, bool
     * True indicates belt is buckled.
     */
    public static final int ID_SEAT_BELT_BUCKLED = 0x2003;
    /**
     * seat belt height position, int
     * Adjusts the shoulder belt anchor point.
     * Max value indicates highest position
     * Min value indicates lowest position
     */
    public static final int ID_SEAT_BELT_HEIGHT_POS = 0x2004;
    /** seat belt height move, int */
    public static final int ID_SEAT_BELT_HEIGHT_MOVE = 0x2005;
    /**
     * seat fore/aft position, int
     * Sets the seat position forward (closer to steering wheel) and backwards.
     * Max value indicates closest to wheel, min value indicates most rearward
     * position.
     */
    public static final int ID_SEAT_FORE_AFT_POS = 0x2006;
    /** seat fore/aft move, int */
    public static final int ID_SEAT_FORE_AFT_MOVE = 0x2007;
    /**
     * seat backrest angle #1 position, int
     * Backrest angle 1 is the actuator closest to the bottom of the seat.
     * Max value indicates angling forward towards the steering wheel.
     * Min value indicates full recline.
     */
    public static final int ID_SEAT_BACKREST_ANGLE_1_POS = 0x2008;
    /** seat backrest angle #1 move, int */
    public static final int ID_SEAT_BACKREST_ANGLE_1_MOVE = 0x2009;
    /**
     * seat backrest angle #2 position, int
     * Backrest angle 2 is the next actuator up from the bottom of the seat.
     * Max value indicates angling forward towards the steering wheel.
     * Min value indicates full recline.
     */
    public static final int ID_SEAT_BACKREST_ANGLE_2_POS = 0x200A;
    /** seat backrest angle #2 move, int */
    public static final int ID_SEAT_BACKREST_ANGLE_2_MOVE = 0x200B;
    /**
     * seat height position, int
     * Sets the seat height.
     * Max value indicates highest position.
     * Min value indicates lowest position.
     */
    public static final int ID_SEAT_HEIGHT_POS = 0x200C;
    /** seat height move, int */
    public static final int ID_SEAT_HEIGHT_MOVE = 0x200D;
    /**
     * seat depth position, int
     * Sets the seat depth, distance from back rest to front edge of seat.
     * Max value indicates longest depth position.
     * Min value indicates shortest position.
     */
    public static final int ID_SEAT_DEPTH_POS = 0x200E;
    /** seat depth move, int */
    public static final int ID_SEAT_DEPTH_MOVE = 0x200F;
    /**
     * seat tilt position, int
     * Sets the seat tilt.
     * Max value indicates front edge of seat higher than back edge.
     * Min value indicates front edge of seat lower than back edge.
     */
    public static final int ID_SEAT_TILT_POS = 0x2010;
    /** seat tilt move, int */
    public static final int ID_SEAT_TILT_MOVE = 0x2011;
    /**
     * seat lumbar fore/aft position, int
     * Pushes the lumbar support forward and backwards
     * Max value indicates most forward position.
     * Min value indicates most rearward position.
     */
    public static final int ID_SEAT_LUMBAR_FORE_AFT_POS = 0x2012;
    /** seat lumbar fore/aft move, int */
    public static final int ID_SEAT_LUMBAR_FORE_AFT_MOVE = 0x2013;
    /**
     * seat lumbar side support position, int
     * Sets the amount of lateral lumbar support.
     * Max value indicates widest lumbar setting (i.e. least support)
     * Min value indicates thinnest lumbar setting.
     */
    public static final int ID_SEAT_LUMBAR_SIDE_SUPPORT_POS = 0x2014;
    /** seat lumbar side support move, int */
    public static final int ID_SEAT_LUMBAR_SIDE_SUPPORT_MOVE = 0x2015;
    /**
     * seat headrest height position, int
     * Sets the headrest height.
     * Max value indicates tallest setting.
     * Min value indicates shortest setting.
     */
    public static final int ID_SEAT_HEADREST_HEIGHT_POS = 0x2016;
    /** seat headrest heigh move, int */
    public static final int ID_SEAT_HEADREST_HEIGHT_MOVE = 0x2017;
    /**
     * seat headrest angle position, int
     * Sets the angle of the headrest.
     * Max value indicates most upright angle.
     * Min value indicates shallowest headrest angle.
     */
    public static final int ID_SEAT_HEADREST_ANGLE_POS = 0x2018;
    /** seat headrest angle move, int */
    public static final int ID_SEAT_HEADREST_ANGLE_MOVE = 0x2019;
    /**
     * seat headrest fore/aft position, int
     * Adjusts the headrest forwards and backwards.
     * Max value indicates position closest to front of car.
     * Min value indicates position closest to rear of car.
     */
    public static final int ID_SEAT_HEADREST_FORE_AFT_POS = 0x201A;
    /** seat headrest fore/aft move, int */
    public static final int ID_SEAT_HEADREST_FORE_AFT_MOVE = 0x201B;

    /** Window properties are zoned by VehicleWindow */
    /**
     * window position, int
     * Max = window up / closed
     * Min = window down / open
     */
    public static final int ID_WINDOW_POS = 0x3001;
    /** window move, int */
    public static final int ID_WINDOW_MOVE = 0x3002;
    /**
     * window vent position, int
     * This feature is used to control the vent feature on a sunroof.
     *
     * Max = vent open
     * Min = vent closed
     */
    public static final int ID_WINDOW_VENT_POS = 0x3003;
    /** window vent move, int */
    public static final int ID_WINDOW_VENT_MOVE = 0x3004;
    /**
     * window lock, bool
     * True indicates windows are locked and can't be moved.
     */
    public static final int ID_WINDOW_LOCK = 0x3005;

    /** @hide */
    @IntDef({
        ID_DOOR_POS,
        ID_DOOR_MOVE,
        ID_DOOR_LOCK,
        ID_MIRROR_Z_POS,
        ID_MIRROR_Z_MOVE,
        ID_MIRROR_Y_POS,
        ID_MIRROR_Y_MOVE,
        ID_MIRROR_LOCK,
        ID_MIRROR_FOLD,
        ID_SEAT_MEMORY_SELECT,
        ID_SEAT_MEMORY_SET,
        ID_SEAT_BELT_BUCKLED,
        ID_SEAT_BELT_HEIGHT_POS,
        ID_SEAT_BELT_HEIGHT_MOVE,
        ID_SEAT_FORE_AFT_POS,
        ID_SEAT_FORE_AFT_MOVE,
        ID_SEAT_BACKREST_ANGLE_1_POS,
        ID_SEAT_BACKREST_ANGLE_1_MOVE,
        ID_SEAT_BACKREST_ANGLE_2_POS,
        ID_SEAT_BACKREST_ANGLE_2_MOVE,
        ID_SEAT_HEIGHT_POS,
        ID_SEAT_HEIGHT_MOVE,
        ID_SEAT_DEPTH_POS,
        ID_SEAT_DEPTH_MOVE,
        ID_SEAT_TILT_POS,
        ID_SEAT_TILT_MOVE,
        ID_SEAT_LUMBAR_FORE_AFT_POS,
        ID_SEAT_LUMBAR_FORE_AFT_MOVE,
        ID_SEAT_LUMBAR_SIDE_SUPPORT_POS,
        ID_SEAT_LUMBAR_SIDE_SUPPORT_MOVE,
        ID_SEAT_HEADREST_HEIGHT_POS,
        ID_SEAT_HEADREST_HEIGHT_MOVE,
        ID_SEAT_HEADREST_ANGLE_POS,
        ID_SEAT_HEADREST_ANGLE_MOVE,
        ID_SEAT_HEADREST_FORE_AFT_POS,
        ID_SEAT_HEADREST_FORE_AFT_MOVE,
        ID_WINDOW_POS,
        ID_WINDOW_MOVE,
        ID_WINDOW_VENT_POS,
        ID_WINDOW_VENT_MOVE,
        ID_WINDOW_LOCK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PropertyId {}

    public interface CarCabinEventCallback {
        /** Called when a property is updated */
        void onChangeEvent(CarPropertyValue value);

        /** Called when an error is detected with a property */
        void onErrorEvent(@PropertyId int propertyId, int zone);
    }

    private static class CarPropertyEventListenerToBase implements CarPropertyEventCallback {
        private final WeakReference<CarCabinManager> mManager;

        public CarPropertyEventListenerToBase(CarCabinManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onChangeEvent(CarPropertyValue value) {
            CarCabinManager manager = mManager.get();
            if (manager != null) {
                manager.handleOnChangeEvent(value);
            }
        }

        @Override
        public void onErrorEvent(int propertyId, int zone) {
            CarCabinManager manager = mManager.get();
            if (manager != null) {
                manager.handleOnErrorEvent(propertyId, zone);
            }
        }
    }

    void handleOnChangeEvent(CarPropertyValue value) {
        Collection<CarCabinEventCallback> callbacks;
        synchronized (this) {
            callbacks = new ArraySet<>(mCallbacks);
        }
        for (CarCabinEventCallback l: callbacks) {
            l.onChangeEvent(value);
        }
    }

    void handleOnErrorEvent(int propertyId, int zone) {
        Collection<CarCabinEventCallback> listeners;
        synchronized (this) {
            listeners = new ArraySet<>(mCallbacks);
        }
        if (!listeners.isEmpty()) {
            for (CarCabinEventCallback l: listeners) {
                l.onErrorEvent(propertyId, zone);
            }
        }
    }

    /**
     * Get an instance of the CarCabinManager.
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     * @hide
     */
    public CarCabinManager(IBinder service, Context context, Handler handler) {
        mMgr = new CarPropertyManagerBase(service, handler, DBG, TAG);
    }

    /** Returns true if the property is a zoned type. */
    public static boolean isZonedProperty(@PropertyId int propertyId) {
        return true;
    }

    /** Implement wrappers for contained CarPropertyManagerBase object */
    public synchronized void registerCallback(CarCabinEventCallback callback) throws
            CarNotConnectedException {
        if (mCallbacks.isEmpty()) {
            mListenerToBase = new CarPropertyEventListenerToBase(this);
            mMgr.registerCallback(mListenerToBase);
        }
        mCallbacks.add(callback);
    }

    public synchronized void unregisterCallback(CarCabinEventCallback callback) throws
            CarNotConnectedException {
        mCallbacks.remove(callback);
        if (mCallbacks.isEmpty()) {
            mMgr.unregisterCallback();
            mListenerToBase = null;
        }
    }

    public List<CarPropertyConfig> getPropertyList() throws CarNotConnectedException {
        return mMgr.getPropertyList();
    }

    public boolean getBooleanProperty(@PropertyId int propertyId, int area)
            throws CarNotConnectedException {
        return mMgr.getBooleanProperty(propertyId, area);
    }

    public float getFloatProperty(@PropertyId int propertyId, int area)
            throws CarNotConnectedException {
        return mMgr.getFloatProperty(propertyId, area);
    }

    public int getIntProperty(@PropertyId int propertyId, int area)
            throws CarNotConnectedException {
        return mMgr.getIntProperty(propertyId, area);
    }

    public void setBooleanProperty(@PropertyId int propertyId, int area, boolean val)
            throws CarNotConnectedException {
        mMgr.setBooleanProperty(propertyId, area, val);
    }

    public void setFloatProperty(@PropertyId int propertyId, int area, float val)
            throws CarNotConnectedException {
        mMgr.setFloatProperty(propertyId, area, val);
    }

    public void setIntProperty(@PropertyId int propertyId, int area, int val)
            throws CarNotConnectedException {
        mMgr.setIntProperty(propertyId, area, val);
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        mMgr.onCarDisconnected();
    }
}
