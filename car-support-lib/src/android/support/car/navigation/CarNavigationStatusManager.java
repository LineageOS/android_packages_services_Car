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

import android.os.Bundle;
import android.support.car.CarManagerBase;
import android.support.car.CarNotConnectedException;

/**
 * APIs for providing navigation status to the instrument cluster. For cars that have a navigation
 * display built into the instrument cluster, a navigation application should also provide
 * turn-by-turn information to the cluster through this manager.
 * <p/>
 * Navigation applications should first call
 * {@link android.support.car.CarAppFocusManager#requestAppFocus(int,
 * android.support.car.CarAppFocusManager.OnAppFocusOwnershipCallback)} and request
 * {@link android.support.car.CarAppFocusManager#APP_FOCUS_TYPE_NAVIGATION}.
 * <p/>
 * After navigation focus is granted, applications should call {@code
 * sendNavigationStatus(STATUS_ACTIVE);} to initialize the cluster and let it know the app will be
 * sending turn events. Then, for each turn of the turn-by-turn guidance, the app calls {@link
 * #sendNavigationTurnEvent(int, CharSequence, int, int, int)}; this sends image data to the cluster
 * (and is why that data is not sent in subsequent turn distance events). To update the distance
 * and time to the next turn, the app should make periodic calls to {@link
 * #sendNavigationTurnDistanceEvent(int, int, int, int)}.
 * <p/>
 * Calling {@code sendNavigationStatus(STATUS_INACTIVE);} when the route is completed allows the
 * car to use the cluster panel for other data (such as media, weather, etc.) and is what a well
 * behaved app is expected to do.
 */
public abstract class CarNavigationStatusManager implements CarManagerBase {

    /**
     * Listener for navigation related events. Callbacks are called in the Looper context.
     */
    public interface CarNavigationCallback {
        /**
         * Instrument Cluster started in navigation mode.
         * @param manager The manager the callback is attached to.  Useful if the app wishes to
         * unregister.
         * @param instrumentCluster An object describing the configuration and state of the car's
         * navigation instrument cluster.
         */
        void onInstrumentClusterStarted(CarNavigationStatusManager manager,
                CarNavigationInstrumentCluster instrumentCluster);

        /**
         * Instrument cluster ended.
         * @param manager The manager the callback is attached to.  Useful if the app wished to
         * unregister.
         */
        void onInstrumentClusterStopped(CarNavigationStatusManager manager);
    }

    /**
     * Sends events from navigation app to instrument cluster.
     *
     * @param eventType event type, the value could be either
     * {@link #EVENT_TYPE_NEXT_MANEUVER_INFO}, {@link EVENT_TYPE_NEXT_MANEUVER_COUNTDOWN}, or
     * vendor-specific code.
     *
     * @param bundle object that holds data about the event
     * @throws android.car.CarNotConnectedException if the connection to the car service has been lost.
     */
    public abstract void sendEvent(int eventType, Bundle bundle)
            throws CarNotConnectedException;

    /**
     * @param callback {@link CarNavigationCallback} to be registered, replacing any existing
     * listeners.
     * @throws CarNotConnectedException if the connection to the car service has been lost.
     */
    public abstract void addListener(CarNavigationCallback callback)
            throws CarNotConnectedException;

    /**
     * Unregister the {@link CarNavigationCallback} associated with this instance.
     */
    public abstract void removeListener();
}
