/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.builtin.util;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.usage.Flags;
import android.app.usage.UsageStatsManager;
import android.os.PersistableBundle;
import android.util.Log;

import java.util.Objects;

/**
 * Wrapper for UsageStatsManager.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class UsageStatsManagerHelper {

    private static final String TAG = UsageStatsManagerHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private UsageStatsManagerHelper() {
        throw new UnsupportedOperationException();
    }

    /**
     * Reports user interaction with a given package in the given user with {@code extras}.
     * Check {@link UsageStatsManager#reportUserInteraction(String, int, PersistableBundle)}.
     * If {@code Flags.userInteractionTypeApi()} is {@code false}, fall back to
     * {@link UsageStatsManager#reportUserInteraction(String, int)}.
     *
     * <p>
     * Note: The structure of {@code extras} is a {@link PersistableBundle} with the
     * category {@link UsageStatsManager#EXTRA_EVENT_CATEGORY} and the action
     * {@link UsageStatsManager#EXTRA_EVENT_ACTION}.
     * Category provides additional detail about the user interaction, the value
     * is defined in namespace based. Example: android.app.notification could be used to
     * indicate that the reported user interaction is related to notification. Action
     * indicates the general action that performed.
     * </p>
     *
     * @param usageStatsManager {@link UsageStatsManager} instance used to report the user
     *                          interaction
     * @param packageName The package name of the app
     * @param userId The user id who triggers the user interaction
     * @param extras The {@link PersistableBundle} that will be used to specify the
     *               extra details for the user interaction event. The {@link PersistableBundle}
     *               must contain the extras {@link UsageStatsManager#EXTRA_EVENT_CATEGORY},
     *               {@link UsageStatsManager#EXTRA_EVENT_ACTION}. Cannot be empty.
     */
    public static void reportUserInteraction(@NonNull UsageStatsManager usageStatsManager,
            @NonNull String packageName, @UserIdInt int userId, @NonNull PersistableBundle extras) {
        if (DEBUG) {
            Log.d(TAG, "usageStatsManager#reportUserInteraction package=" + packageName
                    + " userId=" + userId + " extras=" + extras);
        }
        Objects.requireNonNull(usageStatsManager);
        Objects.requireNonNull(packageName);
        if (Flags.userInteractionTypeApi()) {
            Objects.requireNonNull(extras);
            usageStatsManager.reportUserInteraction(packageName, userId, extras);
        } else {
            usageStatsManager.reportUserInteraction(packageName, userId);
        }
    }

}
