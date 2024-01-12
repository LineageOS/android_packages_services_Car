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

import android.content.Context;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.core.content.ContextCompat;

import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.car.distantdisplay.common.DistantDisplayReceiver;
import com.android.systemui.car.distantdisplay.common.TaskViewController;
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
    private static final boolean DEBUG = Build.IS_ENG || Build.IS_USERDEBUG;
    private final Context mContext;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private boolean mUserSetupInProgress;
    private boolean mIsUserUnlocked;

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

        TaskViewController controller = new TaskViewController(
                mContext,
                mContext.getSystemService(DisplayManager.class),
                (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE));

        if (DEBUG) {
            // TODO(b/319879239): remove the broadcast receiver once the binder service is
            //  implemented.
            setupDebuggingThroughAdb(controller);
        }
    }

    private void setupDebuggingThroughAdb(TaskViewController controller) {
        IntentFilter filter = new IntentFilter(DistantDisplayReceiver.DISTANT_DISPLAY);
        DistantDisplayReceiver receiver = new DistantDisplayReceiver();
        receiver.register(controller::initialize);
        ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }
}
