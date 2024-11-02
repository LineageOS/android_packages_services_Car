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
package android.car.builtin.os;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.storage.StorageManager;

/**
 * Helper for {@link StorageManager}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class StorageManagerHelper {

    private StorageManagerHelper() {
        throw new UnsupportedOperationException("contains only static members");
    }

    /**
     * Returns whether or not a user's storage is currently unlocked
     */
    public static boolean isUserStorageUnlocked(@UserIdInt int userId) {
        return StorageManager.isCeStorageUnlocked(userId);
    }

    /**
     * Locks the user storage for the provided userId.
     * @return true if the user storage was successfully locked
     */
    public static boolean lockUserStorage(@NonNull Context context, @UserIdInt int userId) {
        StorageManager sm = context.getSystemService(StorageManager.class);
        if (sm != null) {
            sm.lockCeStorage(userId);
            return !isUserStorageUnlocked(userId);
        }
        return false;
    }
}
