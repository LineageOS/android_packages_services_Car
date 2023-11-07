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
package com.android.systemui.car.distantdisplay.activity.window;

import android.app.ActivityOptions;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.DistantDisplayActivity;
import com.android.systemui.car.distantdisplay.common.UserUnlockReceiver;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Controller that decides who will host the TaskView based on the flags. Host can either be an
 * activity window or a window hosted by systemUI. This controller is also responsible for creating
 * the win systemUI window and attaching view to it if needed.
 */
@SysUISingleton
public class ActivityWindowController {
    public static final String TAG = ActivityWindowController.class.getSimpleName();
    private final UserUnlockReceiver mUserUnlockReceiver = new UserUnlockReceiver();
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final int mDistantDisplayId;

    @Inject
    public ActivityWindowController(Context context) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mDistantDisplayId = findDisplay(
                mContext.getResources().getInteger(R.integer.config_distantDisplayId));
    }

    /**
     * Initializes the window / activity to host TaskViews.
     */
    public void initialize() {
        registerUserUnlockReceiver();
    }

    private void registerUserUnlockReceiver() {
        UserUnlockReceiver.Callback callback = () -> {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
            options.setLaunchDisplayId(mDistantDisplayId);
            mContext.startActivityAsUser(DistantDisplayActivity.createIntent(mContext),
                    options.toBundle(), UserHandle.CURRENT);
        };
        mUserUnlockReceiver.register(mContext, callback);
    }

    private int findDisplay(int distantDisplayId) {
        for (Display display : mDisplayManager.getDisplays()) {
            if (display.getDisplayId() == distantDisplayId) {
                return display.getDisplayId();
            }
        }
        Log.e(TAG, "Could not find a display id" + distantDisplayId);
        return Display.INVALID_DISPLAY;
    }
}
