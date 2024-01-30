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
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
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
    private final int mDistantDisplayId;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private boolean mUserSetupInProgress;
    private boolean mIsUserUnlocked;
    private boolean mDistantDisplayActivityStarted;

    private final CarDeviceProvisionedListener mCarDeviceProvisionedListener =
            new CarDeviceProvisionedListener() {
                @Override
                public void onUserSetupInProgressChanged() {
                    mUserSetupInProgress = mCarDeviceProvisionedController
                            .isCurrentUserSetupInProgress();

                    startDistantDisplayActivity();
                }
            };

    @Inject
    public ActivityWindowController(Context context,
            CarDeviceProvisionedController deviceProvisionedController) {
        mContext = context;
        mDistantDisplayId =
                mContext.getResources().getInteger(R.integer.config_distantDisplayId);
        mCarDeviceProvisionedController = deviceProvisionedController;
        mCarDeviceProvisionedController.addCallback(mCarDeviceProvisionedListener);
    }

    /**
     * Initializes the window / activity to host TaskViews.
     */
    public void initialize() {
        registerUserUnlockReceiver();
    }

    private void registerUserUnlockReceiver() {
        UserUnlockReceiver.Callback callback = () -> {
            mIsUserUnlocked = true;
            startDistantDisplayActivity();
        };
        mUserUnlockReceiver.register(mContext, callback);
    }

    private void startDistantDisplayActivity() {
        if (mUserSetupInProgress) {
            Log.w(TAG, "user unlocked but suw still in progress, can't launch activity");
            return;
        }
        if (!mIsUserUnlocked) {
            Log.w(TAG, "user is NOT unlocked, can't launch activity");
            return;
        }
        // check if the activity is already started.
        if (mDistantDisplayActivityStarted) {
            Log.w(TAG, "distant display activity already started");
            return;
        }
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        options.setLaunchDisplayId(mDistantDisplayId);
        mContext.startActivityAsUser(DistantDisplayActivity.createIntent(mContext),
                options.toBundle(), UserHandle.CURRENT);
        mDistantDisplayActivityStarted = true;
    }
}
