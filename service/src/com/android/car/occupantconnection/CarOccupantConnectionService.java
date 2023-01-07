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

package com.android.car.occupantconnection;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IConnectionRequestCallback;
import android.car.occupantconnection.IConnectionStateCallback;
import android.car.occupantconnection.IOccupantZoneStateCallback;
import android.car.occupantconnection.IPayloadCallback;
import android.car.occupantconnection.Payload;
import android.content.pm.PackageInfo;

import com.android.car.CarServiceBase;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;

/**
 * Service to implement API defined in
 * {@link android.car.occupantconnection.CarOccupantConnectionManager} and
 * {@link android.car.CarRemoteDeviceManager}.
 */
public class CarOccupantConnectionService extends ICarOccupantConnection.Stub implements
        CarServiceBase {

    public CarOccupantConnectionService() {
    }

    @Override
    public void init() {
    }

    @Override
    public void release() {
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    /** Run `adb shell dumpsys car_service --services CarOccupantConnectionService` to dump. */
    public void dump(IndentingPrintWriter writer) {
    }

    @Override
    public void registerOccupantZoneStateCallback(IOccupantZoneStateCallback callback) {
    }

    @Override
    public void unregisterOccupantZoneStateCallback() {
    }

    @Override
    public PackageInfo getEndpointPackageInfo(OccupantZoneInfo occupantZone) {
        return null;
    }

    @Override
    public void registerReceiver(String receiverEndpointId, IPayloadCallback callback) {
        // TODO(b/257118072): handle client death (by registering death receipant).
    }

    @Override
    public void unregisterReceiver(String receiverEndpointId) {
    }

    @Override
    public void requestConnection(OccupantZoneInfo receiverZone,
            IConnectionRequestCallback callback) {
    }

    @Override
    public void cancelConnection(OccupantZoneInfo receiverZone) {
    }

    @Override
    public void sendPayload(OccupantZoneInfo receiverZone,
            Payload payload) {
    }

    @Override
    public void disconnect(OccupantZoneInfo receiverZone) {
    }

    @Override
    public boolean isConnected(OccupantZoneInfo receiverZone) {
        return false;
    }

    @Override
    public void registerConnectionStateCallback(OccupantZoneInfo receiverZone,
            IConnectionStateCallback callback) {
    }

    @Override
    public void unregisterConnectionStateCallback(OccupantZoneInfo receiverZone) {
    }
}
