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

package com.android.systemui.car.systembar;

import static com.android.systemui.car.displayarea.DisplayAreaComponent.COLLAPSE_APPLICATION_PANEL;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.AttributeSet;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * CarUiPortraitSystemBarButton is an extension of {@link CarSystemBarButton} that disables itself
 * until it receives a signal from launcher that tasks views are ready.
 */
public class CarUiPortraitSystemBarButton extends CarSystemBarButton {

    private static final String TAG = CarUiPortraitSystemBarButton.class.getSimpleName();
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;

    private final Context mContext;

    public CarUiPortraitSystemBarButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    protected void collapseApplicationPanel() {
        Intent intent = new Intent(COLLAPSE_APPLICATION_PANEL);
        LocalBroadcastManager.getInstance(mContext.getApplicationContext()).sendBroadcast(intent);
    }
}
