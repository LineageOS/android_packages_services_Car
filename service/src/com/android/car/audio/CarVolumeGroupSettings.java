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
package com.android.car.audio;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * Use to save/load car volume group settings
 */
public class CarVolumeGroupSettings {

    // The trailing slash forms a directory-liked hierarchy and
    // allows listening for both GROUP/MEDIA and GROUP/NAVIGATION.
    private static final String VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX = "android.car.VOLUME_GROUP/";

    /**
     * Gets the key to persist volume for a volume group in settings
     *
     * @param zoneId The audio zone id
     * @param groupId The volume group id
     * @return Key to persist volume index for volume group in system settings
     */
    static String getVolumeSettingsKeyForGroup(int zoneId, int groupId) {
        final int maskedGroupId = (zoneId << 8) + groupId;
        return VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX + maskedGroupId;
    }

    private final ContentResolver mContentResolver;

    CarVolumeGroupSettings(Context context) {
        mContentResolver = context.getContentResolver();
    }

    int getStoredVolumeGainIndexForUser(int userId, int zoneId, int id) {
        return Settings.System.getIntForUser(mContentResolver,
                getVolumeSettingsKeyForGroup(zoneId, id), -1, userId);
    }

    void storeVolumeGainIndexForUser(int userId, int zoneId, int id, int gainIndex) {
        Settings.System.putIntForUser(mContentResolver,
                getVolumeSettingsKeyForGroup(zoneId, id),
                gainIndex, userId);
    }
}
