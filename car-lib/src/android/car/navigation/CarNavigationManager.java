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
package android.car.navigation;

import android.annotation.IntDef;
import android.car.CarApiUtil;
import android.car.CarLibLog;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.car.cluster.renderer.IInstrumentClusterNavigation;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * API for providing navigation status for instrument cluster.
 * @hide
 */
public class CarNavigationManager implements CarManagerBase {

    /** Navigation status */
    public static final int STATUS_UNAVAILABLE = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_INACTIVE = 2;
    /** Turn Types */
    public static final int TURN_UNKNOWN = 0;
    public static final int TURN_DEPART = 1;
    public static final int TURN_NAME_CHANGE = 2;
    public static final int TURN_SLIGHT_TURN = 3;
    public static final int TURN_TURN = 4;
    public static final int TURN_SHARP_TURN = 5;
    public static final int TURN_U_TURN = 6;
    public static final int TURN_ON_RAMP = 7;
    public static final int TURN_OFF_RAMP = 8;
    public static final int TURN_FORK = 9;
    public static final int TURN_MERGE = 10;
    public static final int TURN_ROUNDABOUT_ENTER = 11;
    public static final int TURN_ROUNDABOUT_EXIT = 12;
    public static final int TURN_ROUNDABOUT_ENTER_AND_EXIT = 13;
    public static final int TURN_STRAIGHT = 14;
    public static final int TURN_FERRY_BOAT = 16;
    public static final int TURN_FERRY_TRAIN = 17;
    public static final int TURN_DESTINATION = 19;
    /** Turn Side */
    public static final int TURN_SIDE_LEFT = 1;
    public static final int TURN_SIDE_RIGHT = 2;
    public static final int TURN_SIDE_UNSPECIFIED = 3;

    private static final int START = 1;
    private static final int STOP = 2;

    /**
     * Distance units for use in {@link #sendNavigationTurnDistanceEvent(int, int, int, int)}.
     * DISTANCE_KILOMETERS_P1 and DISTANCE_MILES_P1 are the same as their respective
     * units, except they require that the head unit display at least 1 digit after the
     * decimal (e.g. 2.0).
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DisplayDistanceUnit.METERS,
            DisplayDistanceUnit.KILOMETERS,
            DisplayDistanceUnit.KILOMETERS_P1,
            DisplayDistanceUnit.MILES,
            DisplayDistanceUnit.MILES_P1,
            DisplayDistanceUnit.FEET,
            DisplayDistanceUnit.YARDS
    })
    public @interface DisplayDistanceUnit {
        int METERS = 1;
        int KILOMETERS = 2;
        int KILOMETERS_P1 = 3;
        int MILES = 4;
        int MILES_P1 = 5;
        int FEET = 6;
        int YARDS = 7;
    }

    private static final String TAG = CarLibLog.TAG_NAV;

    private final IInstrumentClusterNavigation mService;


    /**
     * Only for CarServiceLoader
     * @hide
     */
    public CarNavigationManager(IBinder service) {
        mService = IInstrumentClusterNavigation.Stub.asInterface(service);
    }

    /**
     * @param status new instrument cluster navigation status.
     * @return true if successful.
     * @throws CarNotConnectedException if the connection to the car service has been lost.
     */
    public boolean sendNavigationStatus(int status) throws CarNotConnectedException {
        try {
            if (status == STATUS_ACTIVE) {
                mService.onStartNavigation();
            } else {
                mService.onStopNavigation();
            }
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            handleCarServiceRemoteExceptionAndThrow(e);
            return false;
        }
        return true;
    }

    /**
     * Sends a Navigation Next Step event to the car.
     * <p>
     * Note: For an example of a roundabout: if a roundabout has 4 exits, spaced evenly, then the
     * first exit will have turnNumber=1, turnAngle=90; the second will have turnNumber=2,
     * turnAngle=180; the third will have turnNumber=3, turnAngle=270.  turnNumber and turnAngle are
     * counted in the direction of travel around the roundabout (clockwise for roads where the car
     * drives on the left-hand side of the road, such as Australia; anti-clockwise for roads where
     * the car drives on the right, such as the USA).
     *
     * @param event event type ({@link #TURN_TURN}, {@link #TURN_U_TURN},
     *        {@link #TURN_ROUNDABOUT_ENTER_AND_EXIT}, etc).
     * @param road Name of the road
     * @param turnAngle turn angle in degrees between the roundabout entry and exit (0..359).  Only
     *        used for event type {@link #TURN_ROUNDABOUT_ENTER_AND_EXIT}.  -1 if unused.
     * @param turnNumber turn number, counting around from the roundabout entry to the exit.  Only
     *        used for event type {@link #TURN_ROUNDABOUT_ENTER_AND_EXIT}.  -1 if unused.
     * @param image image to be shown in the instrument cluster.  Null if instrument
     *        cluster type doesn't support images.
     * @param turnSide turn side ({@link #TURN_SIDE_LEFT}, {@link #TURN_SIDE_RIGHT} or
     *        {@link #TURN_SIDE_UNSPECIFIED}).
     * @return true if successful.
     * @throws CarNotConnectedException if the connection to the car service has been lost.
     *
     */
    public boolean sendNavigationTurnEvent(int event, String road, int turnAngle, int turnNumber,
            Bitmap image, int turnSide) throws CarNotConnectedException {
        try {
            mService.onNextManeuverChanged(event, road, turnAngle, turnNumber, image, turnSide);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            handleCarServiceRemoteExceptionAndThrow(e);
            return false;
        }
        return true;
    }

    /**
     * Sends a Navigation Next Step Distance event to the car.
     *
     * @param distanceMeters Distance to next event in meters.
     * @param timeSeconds Time to next event in seconds.
     * @param displayDistanceMillis Distance to the next event. This is exactly the same distance
     * that navigation app is displaying. Use it when you want to display distance, it has
     * appropriate rounding function and units are in sync with navigation app. This parameter is
     * in {@code displayDistanceUnit * 1000}.
     * @param displayDistanceUnit units for {@param displayDistanceMillis} param.
     * See {@link DisplayDistanceUnit} for acceptable values.
     * @return true if successful.
     * @throws CarNotConnectedException if the connection to the car service has been lost.
     */
    public boolean sendNavigationTurnDistanceEvent(int distanceMeters, int timeSeconds,
            int displayDistanceMillis, @DisplayDistanceUnit int displayDistanceUnit)
            throws CarNotConnectedException {
        try {
            mService.onNextManeuverDistanceChanged(distanceMeters, timeSeconds,
                    displayDistanceMillis, displayDistanceUnit);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            handleCarServiceRemoteExceptionAndThrow(e);
            return false;
        }
        return true;
    }

    @Override
    public void onCarDisconnected() {
        Log.d(TAG, "onCarDisconnected");
    }

    /** Returns navigation features of instrument cluster */
    public CarNavigationInstrumentCluster getInstrumentClusterInfo()
            throws CarNotConnectedException {
        try {
            return mService.getInstrumentClusterInfo();
        } catch (RemoteException e) {
            handleCarServiceRemoteExceptionAndThrow(e);
        }
        return null;
    }

    private void handleCarServiceRemoteExceptionAndThrow(RemoteException e)
            throws CarNotConnectedException {
        handleCarServiceRemoteException(e);
        throw new CarNotConnectedException();
    }

    private void handleCarServiceRemoteException(RemoteException e) {
        Log.w(TAG, "RemoteException from car service:" + e.getMessage());
        // nothing to do for now
    }
}
