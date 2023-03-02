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

import static android.car.CarOccupantZoneManager.INVALID_USER_ID;

import static com.android.car.CarServiceUtils.assertPermission;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.builtin.util.Slogf;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IConnectionRequestCallback;
import android.car.occupantconnection.IOccupantZoneStateCallback;
import android.car.occupantconnection.IPayloadCallback;
import android.car.occupantconnection.Payload;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.android.car.CarOccupantZoneService;
import com.android.car.CarServiceBase;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.power.CarPowerManagementService;

/**
 * Service to implement API defined in
 * {@link android.car.occupantconnection.CarOccupantConnectionManager} and
 * {@link android.car.CarRemoteDeviceManager}.
 */
public class CarOccupantConnectionService extends ICarOccupantConnection.Stub implements
        CarServiceBase {

    private static final String TAG = CarOccupantConnectionService.class.getSimpleName();

    private final Context mContext;
    private final CarOccupantZoneService mOccupantZoneService;
    private final CarPowerManagementService mPowerManagementService;

    public CarOccupantConnectionService(Context context,
            CarOccupantZoneService occupantZoneService,
            CarPowerManagementService powerManagementService) {
        mContext = context;
        mOccupantZoneService = occupantZoneService;
        mPowerManagementService = powerManagementService;
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
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void unregisterOccupantZoneStateCallback() {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public PackageInfo getEndpointPackageInfo(int occupantZoneId, String packageName) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

        // PackageManager#getPackageInfoAsUser() can do this with few lines, but it's hidden API.
        int userId = mOccupantZoneService.getUserForOccupant(occupantZoneId);
        if (userId == INVALID_USER_ID) {
            Slogf.e(TAG, "Invalid user ID for occupant zone " + occupantZoneId);
            return null;
        }
        UserHandle userHandle = UserHandle.of(userId);
        Context userContext = mContext.createContextAsUser(userHandle, /* flags= */ 0);
        PackageManager pm = userContext.getPackageManager();
        if (pm == null) {
            Slogf.e(TAG, "Failed to get PackageManager as user " + userId);
            return null;
        }
        PackageInfo packageInfo = null;
        try {
            packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(/* value= */ 0));
        } catch (PackageManager.NameNotFoundException e) {
            // The client app should be installed in all occupant zones, so log an error.
            Slogf.e(TAG, "Didn't find " + packageName + " in occupant zone " + occupantZoneId);
        }
        return packageInfo;
    }

    // TODO(b/257117236): replace OccupantZoneInfo with occupantZoneId for this method and
    // other methods.
    @Override
    public void controlOccupantZonePower(OccupantZoneInfo occupantZone, boolean powerOn) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

        int[] displayIds = mOccupantZoneService.getAllDisplaysForOccupantZone(occupantZone.zoneId);
        for (int id : displayIds) {
            mPowerManagementService.setDisplayPowerState(id, powerOn);
        }
    }

    @Override
    public boolean isOccupantZonePowerOn(OccupantZoneInfo occupantZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

        return mOccupantZoneService.areDisplaysOnForOccupantZone(occupantZone.zoneId);
    }

    @Override
    public void registerReceiver(String packageName, String receiverEndpointId,
            IPayloadCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void unregisterReceiver(String receiverEndpointId) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void requestConnection(int requestId,
            OccupantZoneInfo receiverZone,
            IConnectionRequestCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void cancelConnection(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void sendPayload(OccupantZoneInfo receiverZone,
            Payload payload) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void disconnect(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public boolean isConnected(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
        return false;
    }
}
