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

package android.car.builtin.devicepolicy;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.admin.DevicePolicyManager;
import android.os.UserHandle;

/**
 * Helper for accessing hidden API of {@link DevicePolicyManager}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class DevicePolicyManagerHelper {

    /**
     * Adds a user restriction on the {@code targetUser}.
     *
     * @see DevicePolicyManager#addUserRestriction
     */
    public static void addUserRestriction(
            @NonNull DevicePolicyManager dpm, @NonNull String systemEntity,
            @NonNull String key, @NonNull UserHandle targetUser) {
        dpm.addUserRestriction(systemEntity, key, targetUser.getIdentifier());
    }

    /**
     * Clears a user restriction from the {@code targetUser}.
     *
     * @see DevicePolicyManager#clearUserRestriction
     */
    public static void clearUserRestriction(
            @NonNull DevicePolicyManager dpm, @NonNull String systemEntity,
            @NonNull String key, @NonNull UserHandle targetUser) {
        dpm.clearUserRestriction(systemEntity, key, targetUser.getIdentifier());
    }

    private DevicePolicyManagerHelper() {
        throw new UnsupportedOperationException();
    }
}
