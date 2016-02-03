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
import android.support.car.navigation.CarNavigationInstrumentCluster;
import android.support.car.navigation.ICarNavigationStatusEventListener;

/**
 * Binder API for CarNavigationStatusManager.
 * @hide
 * {@CompatibilityApi}
 */
interface ICarNavigationStatus {
    void sendNavigationStatus(int status) = 0;
    void sendNavigationTurnEvent(
        int event, String road, int turnAngle, int turnNumber, in Bitmap image, int turnSide) = 1;
    void sendNavigationTurnDistanceEvent(int distanceMeters, int timeSeconds) = 2;
    boolean isSupported() = 3;
    CarNavigationInstrumentCluster getInfo() = 4;
    boolean registerEventListener(ICarNavigationStatusEventListener listener) = 5;
    boolean unregisterEventListener(ICarNavigationStatusEventListener listener) = 6;
}
