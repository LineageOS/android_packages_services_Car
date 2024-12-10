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

package com.google.android.car.adaslocation;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

// Geo GSR-ISA app is pre-installed in AAOS internal builds for testing purpose. The app doesn't
// provide a privacy policy but a deep link so that OEMs can provide the privacy policy. This simple
// activity is to handle Geo GSR-ISA app's privacy policy uri (gsr://privacy_policy) and provide a
// fake privacy policy url for testing purpose.
public final class PrivacyPolicyActivity extends Activity {
    private static final String URL =
            "https://source.android.com/devices/automotive/location_bypass_policy";

    // Indicates whether the user is navigating back from QR code page.
    private boolean mIsNavigatingBack;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!mIsNavigatingBack) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
            this.startActivity(intent);
            mIsNavigatingBack = true;
        } else {
            super.onDestroy();
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            this.startActivity(intent);
        }
    }
}
