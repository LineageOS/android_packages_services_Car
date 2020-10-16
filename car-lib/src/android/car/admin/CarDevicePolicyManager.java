/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.car.admin;

import static android.os.Process.myUid;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.user.UserRemovalResult;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.EventLog;

import com.android.car.internal.common.EventLogTags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Public interface for managing policies enforced on a device.
 *
 * <p>This is a sub-set of {@link android.app.admin.DevicePolicyManager}, but with the following
 * differences:
 *
 * <ol>
 *   <li>Its methods take in consideration driver-safety restrictions.
 *   <li>Callers doesn't need to be a {@code DPC}, but rather have the TBD Role.
 * </ol>
 *
 * @hide
 */
@SystemApi
@TestApi
public final class CarDevicePolicyManager extends CarManagerBase {

    private final ICarDevicePolicyService mService;

    /**
     * @hide
     */
    public CarDevicePolicyManager(@NonNull Car car, @NonNull IBinder service) {
        this(car, ICarDevicePolicyService.Stub.asInterface(service));
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public CarDevicePolicyManager(@NonNull Car car, @NonNull ICarDevicePolicyService service) {
        super(car);
        mService = service;
    }

    /**
     * Removes the given user.
     *
     * @param user identification of the user to be removed.
     *
     * @return whether the user was successfully removed.
     *
     * @throws SecurityException if the caller app doesn't have the proper permission (either
     * {@code android.Manifest.permission.MANAGE_USERS} or
     * {@code android.Manifest.permission.CREATE_USERS}) or if the caller user is not an admin and
     * is trying to remove any user other than its own user.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS})
    @NonNull
    public RemoveUserResult removeUser(@NonNull UserHandle user) {
        Objects.requireNonNull(user, "user cannot be null");

        int userId = user.getIdentifier();
        int uid = myUid();
        EventLog.writeEvent(EventLogTags.CAR_DP_MGR_REMOVE_USER_REQ, uid, userId);
        int status = RemoveUserResult.STATUS_FAILURE_GENERIC;
        try {
            UserRemovalResult result = mService.removeUser(userId);
            status = result.getStatus();
            return new RemoveUserResult(result);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, new RemoveUserResult(null));
        } finally {
            EventLog.writeEvent(EventLogTags.CAR_DP_MGR_REMOVE_USER_RESP, uid, status);
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }
}
