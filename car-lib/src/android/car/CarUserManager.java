/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.car;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * API to manage users related to car.
 *
 * @hide
 */
@SystemApi
public final class CarUserManager implements CarManagerBase {

    private static final String TAG = CarUserManager.class.getSimpleName();
    private final ICarUserService mService;

    /** @hide */
    @VisibleForTesting
    public CarUserManager(@NonNull IBinder service) {
        mService = ICarUserService.Stub.asInterface(service);
    }

    /**
     * Creates a driver who is a regular user and is allowed to login to the driving occupant zone.
     *
     * @param name The name of the driver to be created.
     * @param admin Whether the created driver will be an admin.
     * @return {@link UserInfo} object of the created driver, or {@code null} if the driver could
     *         not be created.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public UserInfo createDriver(@NonNull String name, boolean admin) {
        try {
            return mService.createDriver(name, admin);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a passenger who is a profile of the given driver.
     *
     * @param name The name of the passenger to be created.
     * @param driverId User id of the driver under whom a passenger is created.
     * @return {@link UserInfo} object of the created passenger, or {@code null} if the passenger
     *         could not be created.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public UserInfo createPassenger(@NonNull String name, @UserIdInt int driverId) {
        try {
            return mService.createPassenger(name, driverId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switches a driver to the given user.
     *
     * @param driverId User id of the driver to switch to.
     * @return {@code true} if user switching succeeds, or {@code false} if it fails.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean switchDriver(@UserIdInt int driverId) {
        try {
            return mService.switchDriver(driverId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all drivers who can occupy the driving zone. Guest users are included in the list.
     *
     * @return the list of {@link UserInfo} who can be a driver on the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<UserInfo> getAllDrivers() {
        try {
            return mService.getAllDrivers();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all passengers under the given driver.
     *
     * @param driverId User id of a driver.
     * @return the list of {@link UserInfo} who is a passenger under the given driver.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<UserInfo> getPassengers(@UserIdInt int driverId) {
        try {
            return mService.getPassengers(driverId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Assigns the passenger to the zone and starts the user if it is not started yet.
     *
     * @param passengerId User id of the passenger to be started.
     * @param zoneId Zone id to which the passenger is assigned.
     * @return {@code true} if the user is successfully started or the user is already running.
     *         Otherwise, {@code false}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean startPassenger(@UserIdInt int passengerId, int zoneId) {
        try {
            return mService.startPassenger(passengerId, zoneId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the given passenger.
     *
     * @param passengerId User id of the passenger to be stopped.
     * @return {@code true} if successfully stopped, or {@code false} if failed.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean stopPassenger(@UserIdInt int passengerId) {
        try {
            return mService.stopPassenger(passengerId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }
}
