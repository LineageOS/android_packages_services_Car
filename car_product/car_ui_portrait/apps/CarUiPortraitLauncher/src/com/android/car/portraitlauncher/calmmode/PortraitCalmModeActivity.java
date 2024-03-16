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

package com.android.car.portraitlauncher.calmmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.car.portraitlauncher.R;

// TODO(b/329713280): Add portrait-specific tests
public class PortraitCalmModeActivity extends FragmentActivity {
    private static final String TAG = PortraitCalmModeActivity.class.getSimpleName();
    private static final boolean DEBUG = Build.isDebuggable();
    private static final String APP_PACKAGE_NAME = "com.android.car.portraitlauncher";
    public static final String INTENT_ACTION_DISMISS_CALM_MODE =
            APP_PACKAGE_NAME + ".ACTION_DISMISS_CALM_MODE";
    public static final Intent INTENT_DISMISS_CALM_MODE;

    static {
        INTENT_DISMISS_CALM_MODE = new Intent();
        INTENT_DISMISS_CALM_MODE.setPackage(APP_PACKAGE_NAME);
        INTENT_DISMISS_CALM_MODE.setAction(INTENT_ACTION_DISMISS_CALM_MODE);
    }

    /** Sends a broadcast intent to end Calm mode
     * @param context used for sending broadcast
     */
    public static void dismissCalmMode(@NonNull Context context) {
        context.sendBroadcast(INTENT_DISMISS_CALM_MODE);
    }

    private final BroadcastReceiver mDismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.v(TAG, "Received broadcast:" + " intent=" + intent
                        + ", extras=" + intent.getExtras() + ", user= " + context.getUser());
            }
            finish();
        }
    };

    private final View.OnApplyWindowInsetsListener mOnApplyWindowInsetsListener =
            (v, insets) -> {
                int insetTypes = WindowInsets.Type.systemBars();
                Insets appliedInsets = insets.getInsets(insetTypes);
                v.setPadding(
                        appliedInsets.left, appliedInsets.top,
                        appliedInsets.right, /* bottom= */ 0);
                return insets.inset(appliedInsets);
            };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDecorFitsSystemWindows(/* decorFitsSystemWindows= */ false);
        getWindow()
                .getDecorView()
                .getRootView()
                .setOnApplyWindowInsetsListener(mOnApplyWindowInsetsListener);
        registerBroadcastReceiver();
        setContentView(R.layout.calm_mode_activity);
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(PortraitCalmModeActivity.INTENT_ACTION_DISMISS_CALM_MODE);
        registerReceiver(mDismissReceiver, filter, /* broadcastPermission= */ null,
                /* scheduler= */ null, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDismissReceiver);
    }

}
