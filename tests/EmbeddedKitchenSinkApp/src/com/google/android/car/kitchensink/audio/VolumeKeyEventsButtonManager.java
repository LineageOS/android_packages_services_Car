/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.car.kitchensink.audio;

import android.car.CarOccupantZoneManager;
import android.car.input.CarInputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

final class VolumeKeyEventsButtonManager {

    private static final String TAG = VolumeKeyEventsButtonManager.class.getSimpleName();

    private final CarInputManager mCarInputManager;
    private final CarOccupantZoneManager mCarOccupantZoneManager;

    VolumeKeyEventsButtonManager(CarInputManager carInputManager,
            CarOccupantZoneManager occupantZoneManager) {
        mCarInputManager = carInputManager;
        mCarOccupantZoneManager = occupantZoneManager;
    }

    void sendClickEvent(int keyCode, int repeatCount) {
        // TODO(b/247170915) : Clean code up when MZ volume is handled
        long downTime = SystemClock.uptimeMillis();
        int displayId = mCarOccupantZoneManager
                .getDisplayForOccupant(mCarOccupantZoneManager.getMyOccupantZone(),
                        CarOccupantZoneManager.DISPLAY_TYPE_MAIN).getDisplayId();
        KeyEvent keyEvent =
                new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, repeatCount);
        keyEvent.setDisplayId(displayId);

        Log.i(TAG, "sending key event " + keyEvent);
        mCarInputManager.injectKeyEvent(keyEvent, CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
    }
}
