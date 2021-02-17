/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.google.android.car.networking.preferenceupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter;
import com.google.android.car.networking.preferenceupdater.components.PersonalStorage;

/**
 * This class is responsible to apply PANS Policy if it was requested to be persisted within in the
 * application. If it was requested, PersonalStorage should have information about it.
 */
public final class ApplyPansOnBootCompleteReceiver extends BroadcastReceiver {
    private static final String TAG = ApplyPansOnBootCompleteReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        // Since we are listening to "android.intent.action.BOOT_COMPLETED" only, we don't need
        // to validate nor check if we are triggered on the right intent. Though let's log it.
        Log.v(TAG, "Action intent received. User: " + context.getUserId() + ", Intent:" + intent);

        PersonalStorage storage = new PersonalStorage(context);
        if (storage.getReapplyPansOnBootCompleteState()) {
            // This means we have PANS reapply logic on boot_complete enabled. Applying.
            Log.v(TAG, "PANS Reapply logic enabled. Applying logic from PersonalStorage");

            // Get all preferences from PersonalStorage
            new OemNetworkPreferencesAdapter(context).applyPreference(storage.getAllPrefs());
        } else {
            // This means PANS reapply logic is disabled
            Log.v(TAG, "NetworkPreferencesApp reapply PANS logic is disabled.");
        }
    }
}
