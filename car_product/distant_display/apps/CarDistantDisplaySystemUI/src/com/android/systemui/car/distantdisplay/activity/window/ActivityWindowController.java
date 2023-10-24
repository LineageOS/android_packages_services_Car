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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.MainThread;

import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.car.distantdisplay.activity.DistantDisplayActivity;
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
    public static final String TAG =
            ActivityWindowController.class.getSimpleName();
    private final UserUnlockReceiver mUserUnlockReceiver = new UserUnlockReceiver();
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final int mDistantDisplayId;

    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private boolean mUserSetupInProgress;
    private boolean mIsUserUnlocked;
    private boolean mDistantDisplayActivityStarted;

    private Context mWindowContext;
    private WindowManager mWindowManager;

    private boolean mHostTaskViewInSystemUiWindow = false;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Display display = mDisplayManager.getDisplay(displayId);
                    if (display == null) {
                        Log.w(TAG, "display not found ");
                        return;
                    }
                    Log.d(TAG, "onDisplayAdded: id=" + displayId);
                    if (!(display.getDisplayId() == mDistantDisplayId)) {
                        Log.w(TAG, "not the distant display id, skip " + display.getDisplayId());
                        return;
                    }
                    setUpActivityDisplay(display);
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                }
            };

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
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mDistantDisplayId = mContext.getResources().getInteger(R.integer.distant_display_id);
        mCarDeviceProvisionedController = deviceProvisionedController;
        mCarDeviceProvisionedController.addCallback(mCarDeviceProvisionedListener);
    }

    /**
     * Initializes the window / activity to host TaskViews.
     */
    public void initialize() {
        // TODO(b/303072229): replace this boolean with a flag
        if (mHostTaskViewInSystemUiWindow) {
            boolean hasActivityDisplay = false;
            for (Display display : mDisplayManager.getDisplays()) {
                if (!(display.getDisplayId() == mDistantDisplayId)) continue;
                setUpActivityDisplay(display);
                hasActivityDisplay = true;
            }
            if (!hasActivityDisplay) {
                mDisplayManager.registerDisplayListener(mDisplayListener,
                        mContext.getMainThreadHandler());
            }
        } else {
            registerUserUnlockReceiver();
        }
    }

    private void setUpActivityDisplay(Display display) {
        mWindowContext = mContext.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        mWindowManager = mWindowContext.getSystemService(WindowManager.class);

        inflate();
    }

    @MainThread
    private void inflate() {
        ViewGroup mLayout = (ViewGroup) LayoutInflater.from(mWindowContext)
                .inflate(TaskViewController.getTaskViewContainerLayoutResId(),
                        /* root= */null, /* attachToRoot= */ false);

        WindowManager.LayoutParams mWmLayoutParams = createWindowManagerLayoutParams();
        mWindowManager.addView(mLayout, mWmLayoutParams);

        TaskViewController controller =
                new TaskViewController(mWindowContext);
        controller.initialize(mLayout, false);
    }

    private WindowManager.LayoutParams createWindowManagerLayoutParams() {
        WindowManager.LayoutParams mWmLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mWmLayoutParams.setTrustedOverlay();

        mWmLayoutParams.setFitInsetsTypes(0);
        mWmLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mWmLayoutParams.token = new Binder();
        mWmLayoutParams.setTitle("CarUiDistantDisplayActivityWindow!");
        mWmLayoutParams.packageName = mContext.getPackageName();
        mWmLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWmLayoutParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        return mWmLayoutParams;
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
        options.setLaunchDisplayId(
                mContext.getResources().getInteger(R.integer.distant_display_id));
        mContext.startActivityAsUser(DistantDisplayActivity.createIntent(mContext),
                options.toBundle(), UserHandle.CURRENT);
        mDistantDisplayActivityStarted = true;
    }
}
