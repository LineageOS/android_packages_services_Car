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
package android.support.car.navigation;

import android.graphics.Bitmap;
import android.support.car.Car;
import android.support.car.CarAppFocusManager;
import android.support.car.CarManagerBase;
import android.support.car.CarNotConnectedException;

/**
 * API for providing navigation status to the instrument cluster.
 * <p/>
 * Some cars come equipped with a navigation display built into the instrument cluster.  For
 * these cars a navigation application should also provider turn by turn information to the
 * cluster through this manager.
 * <p/>
 * Navigation applications should first call
 * {@link CarAppFocusManager#requestAppFocus(int, CarAppFocusManager.AppFocusOwnershipChangeListener)}
 * and request {@link CarAppFocusManager#APP_FOCUS_TYPE_NAVIGATION}.  Once Nav Focus is granted
 * they can request this manager via  {@link Car#getCarManager(String)}.  If the car does not have
 * an instrument cluster a null value will be returned.
 * <p/>
 * Once the connection to the cluster is established applications should first call
 * {@code sendNavigationStatus(STATUS_ACTIVE);} to initialize the cluster and let it know you
 * will be sending turn events. For each turn of your turn-by-turn guidance you will first call
 * {@link #sendNavigationTurnEvent(int, String, int, int, int)}.  Among other things, this will
 * send image data to the cluster (and is why we don't want to send it with subsequent
 * turn distance events).  After that you'll want to make periodic calls to
 * {@link #sendNavigationTurnDistanceEvent(int, int, int, int)} to update the distance and time
 * to the next turn.
 * <p/>
 * Calling {@code sendNavigationStatus(STATUS_INACTIVE);} when the route is completed allows the car
 * to use the cluster panel for other data like media information, weather, etc. and is what a
 * well behaved app is expected to do.
 */
public interface CarNavigationStatusManager extends CarManagerBase {

    /**
     * Listener navigation related events. Callbacks are called in the Looper context.
     */
    public interface CarNavigationListener {
        /**
         * Instrument Cluster started in navigation mode
         */
        void onInstrumentClusterStart(CarNavigationInstrumentCluster instrumentCluster);

        /**
         * Instrument cluster ended
         */
        void onInstrumentClusterStop();
    }

    /**
     * Navigation status
     */
    public static final int STATUS_UNAVAILABLE = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_INACTIVE = 2;
    /**
     * Turn Types
     */
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
    /**
     * Turn Side
     */
    public static final int TURN_SIDE_LEFT = 1;
    public static final int TURN_SIDE_RIGHT = 2;
    public static final int TURN_SIDE_UNSPECIFIED = 3;


    /**
     * Distance units for use in {@link #sendNavigationTurnDistanceEvent(int, int, int, int)}.
     * DISTANCE_KILOMETERS_P1 and DISTANCE_MILES_P1 are the same as their respective units, except
     * they require that the head unit display at least 1 digit after the decimal (e.g. 2.0).
     */
    public static final int DISTANCE_METERS = 1;
    public static final int DISTANCE_KILOMETERS = 2;
    public static final int DISTANCE_KILOMETERS_P1 = 3;
    public static final int DISTANCE_MILES = 4;
    public static final int DISTANCE_MILES_P1 = 5;
    public static final int DISTANCE_FEET = 6;
    public static final int DISTANCE_YARDS = 7;

    /**
     * Inform the instrument cluster if navigation is active or not.
     * @param status new instrument cluster navigation status, one of the STATUS_* constants in
     * this class.
     * @return true if successful.
     * @throws CarNotConnectedException
     */
    boolean sendNavigationStatus(int status) throws CarNotConnectedException;

    /**
     * Sends a Navigation Next Step event to the car.
     * <p/>
     * Note: For an example of a roundabout: if a roundabout has 4 exits, spaced evenly, then the
     * first exit will have turnNumber=1, turnAngle=90; the second will have turnNumber=2,
     * turnAngle=180; the third will have turnNumber=3, turnAngle=270.  turnNumber and turnAngle are
     * counted in the direction of travel around the roundabout (clockwise for roads where the car
     * drives on the left-hand side of the road, such as Australia; anti-clockwise for roads where
     * the car drives on the right, such as the USA).
     *
     * @param event event type ({@link #TURN_TURN}, {@link #TURN_U_TURN}, {@link
     * #TURN_ROUNDABOUT_ENTER_AND_EXIT}, etc).
     * @param road Name of the road
     * @param turnAngle turn angle in degrees between the roundabout entry and exit (0..359).  Only
     * used for event type {@link #TURN_ROUNDABOUT_ENTER_AND_EXIT}.  -1 if unused.
     * @param turnNumber turn number, counting around from the roundabout entry to the exit.  Only
     * used for event type {@link #TURN_ROUNDABOUT_ENTER_AND_EXIT}.  -1 if unused.
     * @param turnSide turn side ({@link #TURN_SIDE_LEFT}, {@link #TURN_SIDE_RIGHT} or {@link
     * #TURN_SIDE_UNSPECIFIED}).
     * @return true if successful.
     * @throws CarNotConnectedException
     */
    boolean sendNavigationTurnEvent(int event, String road, int turnAngle, int turnNumber,
            int turnSide) throws CarNotConnectedException;

    /**
     * Same as the public version ({@link #sendNavigationTurnEvent(int, String, int, int, Bitmap,
     * int)}) except a custom image can be sent to the cluster. See documentation for that method.
     *
     * @param image image to be shown in the instrument cluster.  Null if instrument cluster type is
     * {@link CarNavigationInstrumentCluster.ClusterType#IMAGE_CODES_ONLY}, or if the image
     * parameters are malformed (length or width non-positive, or illegal imageColorDepthBits) in
     * the initial NavigationStatusService call.
     *
     * @hide only first party applications may send a custom image to the cluster.
     */
    boolean sendNavigationTurnEvent(int event, String road, int turnAngle, int turnNumber,
            Bitmap image, int turnSide) throws CarNotConnectedException;

    /**
     * Sends a Navigation Next Step Distance event to the car.
     *
     * @param distanceMeters distance to next event in meters.
     * @param timeSeconds time to next event in seconds.
     * @param displayDistanceMillis distance to the next event formatted as it will be displayed by
     * the calling app, in milli-units. For example, 1.25 should be supplied as 1250
     * @param displayDistanceUnit the unit type to use on of the DISTANCE_* types defined in this
     * file.
     * @return true if successful.
     * @throws CarNotConnectedException
     */
    boolean sendNavigationTurnDistanceEvent(int distanceMeters, int timeSeconds,
            int displayDistanceMillis, int displayDistanceUnit) throws CarNotConnectedException;

    /**
     * @param listener {@link CarNavigationListener} to be registered, replacing any existing
     * listeners.
     * @throws CarNotConnectedException
     */
    void registerListener(CarNavigationListener listener) throws CarNotConnectedException;

    /**
     * Unregisters {@link CarNavigationListener}.
     */
    void unregisterListener();
}
