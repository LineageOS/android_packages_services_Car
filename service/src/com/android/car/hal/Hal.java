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

/**
 * Abstraction for vehicle HAL.
 */
public class Hal {

    private final SensorHal mSensorHal;

    private static Hal sInstance;

    public static synchronized Hal getInstance(Context applicationContext) {
        if (sInstance == null) {
            sInstance = new Hal(applicationContext);
            sInstance.init();
        }
        return sInstance;
    }

    public static synchronized void releaseInstance() {
        if (sInstance != null) {
            sInstance.release();
            sInstance = null;
        }
    }

    public Hal(Context applicationContext) {
        mSensorHal = new SensorHal();
    }

    public void init() {
        mSensorHal.init();
    }

    public void release() {
        mSensorHal.release();
    }

    public SensorHal getSensorHal() {
        return mSensorHal;
    }
}
