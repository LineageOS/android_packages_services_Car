/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.wifi;

import android.car.wifi.ICarWifi;
import android.util.proto.ProtoOutputStream;

import com.android.car.CarServiceBase;
import com.android.car.internal.util.IndentingPrintWriter;

/**
 * CarWifiService manages Wi-Fi functionality.
 */
public class CarWifiService extends ICarWifi.Stub implements CarServiceBase {
    public CarWifiService() {}

    @Override
    public void dumpProto(ProtoOutputStream proto) {
        // nothing to do
    }

    @Override
    public void init() {
        // nothing to do
    }

    @Override
    public void release() {
        // nothing to do
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("**CarWifiService**");
    }

    /**
     * Returns {@code true} if the persist tethering settings are able to be changed.
     */
    @Override
    public boolean canControlPersistTetheringSettings() {
        // TODO (b/301660611) Implement canControlPersistTetheringSettings
        return false;
    }
}
