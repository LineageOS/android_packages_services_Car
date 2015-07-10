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

import java.io.PrintWriter;

/**
 * TODO
 * Sensor HAL implementation for physical sensors in car.
 */
public class SensorHal extends SensorHalBase {

    private boolean mIsReady = false;

    @Override
    public synchronized void init() {
        //TODO
        mIsReady = true;
    }

    @Override
    public synchronized void release() {
        //TODO
    }

    @Override
    public synchronized void registerSensorListener(SensorHalBase.SensorListener listener) {
        //TODO
        if (mIsReady) {
            listener.onSensorHalReady(this);
        }
    }

    @Override
    public synchronized boolean isReady() {
        //TODO
        return true;
    }

    @Override
    public synchronized int[] getSupportedSensors() {
        //TODO
        return null;
    }

    @Override
    public synchronized boolean requestSensorStart(int sensorType, int rate) {
        //TODO
        return false;
    }

    @Override
    public synchronized void requestSensorStop(int sensorType) {
        //TODO
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO Auto-generated method stub
    }
}
