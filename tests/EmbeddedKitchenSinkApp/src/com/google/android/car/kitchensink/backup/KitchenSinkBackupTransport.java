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

package com.google.android.car.kitchensink.backup;
import android.app.backup.BackupTransport;
import android.content.ComponentName;
import android.content.Context;

public final class KitchenSinkBackupTransport extends BackupTransport {
    private static final String TRANSPORT_DIR_NAME =
            "com.google.android.car.kitchensink.backup.KitchenSinkBackupTransport";

    private static final String TRANSPORT_DESTINATION_STRING =
            "Backing up to debug-only private cache";

    private static final String TRANSPORT_DATA_MANAGEMENT_LABEL = "";

    private final Context mContext;

    public KitchenSinkBackupTransport(Context context) {
        mContext = context;
    }

    @Override
    public String name() {
        return new ComponentName(mContext, this.getClass()).flattenToShortString();
    }

    @Override
    public String transportDirName() {
        return TRANSPORT_DIR_NAME;
    }

    @Override
    public String currentDestinationString() {
        return TRANSPORT_DESTINATION_STRING;
    }

    @Override
    public CharSequence dataManagementIntentLabel() {
        return TRANSPORT_DATA_MANAGEMENT_LABEL;
    }

}
