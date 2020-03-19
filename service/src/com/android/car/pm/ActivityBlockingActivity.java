/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car.pm;

import static com.android.car.pm.CarPackageManagerService.BLOCKING_INTENT_EXTRA_BLOCKED_ACTIVITY_NAME;
import static com.android.car.pm.CarPackageManagerService.BLOCKING_INTENT_EXTRA_BLOCKED_TASK_ID;
import static com.android.car.pm.CarPackageManagerService.BLOCKING_INTENT_EXTRA_IS_ROOT_ACTIVITY_DO;
import static com.android.car.pm.CarPackageManagerService.BLOCKING_INTENT_EXTRA_ROOT_ACTIVITY_NAME;

import android.app.Activity;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;

import com.android.car.CarLog;
import com.android.car.R;

/**
 * Default activity that will be launched when the current foreground activity is not allowed.
 * Additional information on blocked Activity should be passed as intent extras.
 */
public class ActivityBlockingActivity extends Activity {
    private static final int INVALID_TASK_ID = -1;

    private Car mCar;
    private CarUxRestrictionsManager mUxRManager;

    private Button mExitButton;
    private Button mToggleDebug;

    private int mBlockedTaskId;

    private final View.OnClickListener mOnExitButtonClickedListener =
            v -> {
                if (isExitOptionCloseApplication()) {
                    handleCloseApplication();
                } else {
                    handleRestartingTask();
                }
            };

    private final ViewTreeObserver.OnGlobalLayoutListener mOnGlobalLayoutListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    mToggleDebug.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    updateButtonWidths();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocking);

        mExitButton = findViewById(R.id.exit_button);

        // Listen to the CarUxRestrictions so this blocking activity can be dismissed when the
        // restrictions are lifted.
        // This Activity should be launched only after car service is initialized. Currently this
        // Activity is only launched from CPMS. So this is safe to do.
        mCar = Car.createCar(this, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        return;
                    }
                    mUxRManager = (CarUxRestrictionsManager) car.getCarManager(
                            Car.CAR_UX_RESTRICTION_SERVICE);
                    // This activity would have been launched only in a restricted state.
                    // But ensuring when the service connection is established, that we are still
                    // in a restricted state.
                    handleUxRChange(mUxRManager.getCurrentCarUxRestrictions());
                    mUxRManager.registerListener(ActivityBlockingActivity.this::handleUxRChange);
                });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display info about the current blocked activity, and optionally show an exit button
        // to restart the blocked task (stack of activities) if its root activity is DO.
        mBlockedTaskId = getIntent().getIntExtra(BLOCKING_INTENT_EXTRA_BLOCKED_TASK_ID,
                INVALID_TASK_ID);

        // blockedActivity is expected to be always passed in as the topmost activity of task.
        String blockedActivity = getIntent().getStringExtra(
                BLOCKING_INTENT_EXTRA_BLOCKED_ACTIVITY_NAME);
        if (!TextUtils.isEmpty(blockedActivity)) {
            if (Log.isLoggable(CarLog.TAG_AM, Log.DEBUG)) {
                Log.d(CarLog.TAG_AM, "Blocking activity " + blockedActivity);
            }
        }

        displayExitButton();

        // Show more debug info for non-user build.
        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            displayDebugInfo();
        }
    }

    private void displayExitButton() {
        String exitButtonText = getExitButtonText();

        mExitButton.setText(exitButtonText);
        mExitButton.setOnClickListener(mOnExitButtonClickedListener);
    }

    // If the root activity is DO, the user will have the option to go back to that activity,
    // otherwise, the user will have the option to close the blocked application
    private boolean isExitOptionCloseApplication() {
        boolean isRootDO = getIntent().getBooleanExtra(
                BLOCKING_INTENT_EXTRA_IS_ROOT_ACTIVITY_DO, false);
        return mBlockedTaskId == INVALID_TASK_ID || !isRootDO;
    }

    private String getExitButtonText() {
        return isExitOptionCloseApplication() ? getString(R.string.exit_button_close_application)
                : getString(R.string.exit_button_go_back);
    }

    private void displayDebugInfo() {
        String blockedActivity = getIntent().getStringExtra(
                BLOCKING_INTENT_EXTRA_BLOCKED_ACTIVITY_NAME);
        String rootActivity = getIntent().getStringExtra(BLOCKING_INTENT_EXTRA_ROOT_ACTIVITY_NAME);

        TextView debugInfo = findViewById(R.id.debug_info);
        debugInfo.setText(getDebugInfo(blockedActivity, rootActivity));

        // We still want to ensure driving safety for non-user build;
        // toggle visibility of debug info with this button.
        mToggleDebug = findViewById(R.id.toggle_debug_info);
        mToggleDebug.setVisibility(View.VISIBLE);
        mToggleDebug.setOnClickListener(v -> {
            boolean isDebugVisible = debugInfo.getVisibility() == View.VISIBLE;
            debugInfo.setVisibility(isDebugVisible ? View.GONE : View.VISIBLE);
        });

        mToggleDebug.getViewTreeObserver().addOnGlobalLayoutListener(mOnGlobalLayoutListener);
    }

    // When the Debug button is visible, we set both of the visible buttons to have the width
    // of whichever button is wider
    private void updateButtonWidths() {
        Button debugButton = findViewById(R.id.toggle_debug_info);

        int exitButtonWidth = mExitButton.getWidth();
        int debugButtonWidth = debugButton.getWidth();

        if (exitButtonWidth > debugButtonWidth) {
            debugButton.setWidth(exitButtonWidth);
        } else {
            mExitButton.setWidth(debugButtonWidth);
        }
    }

    private String getDebugInfo(String blockedActivity, String rootActivity) {
        StringBuilder debug = new StringBuilder();

        ComponentName blocked = ComponentName.unflattenFromString(blockedActivity);
        debug.append("Blocked activity is ")
                .append(blocked.getShortClassName())
                .append("\nBlocked activity package is ")
                .append(blocked.getPackageName());

        if (rootActivity != null) {
            ComponentName root = ComponentName.unflattenFromString(rootActivity);
            // Optionally show root activity info if it differs from the blocked activity.
            if (!root.equals(blocked)) {
                debug.append("\n\nRoot activity is ").append(root.getShortClassName());
            }
            if (!root.getPackageName().equals(blocked.getPackageName())) {
                debug.append("\nRoot activity package is ").append(root.getPackageName());
            }
        }
        return debug.toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Finish when blocking activity goes invisible to avoid it accidentally re-surfaces with
        // stale string regarding blocked activity.
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUxRManager.unregisterListener();
        mToggleDebug.getViewTreeObserver().removeOnGlobalLayoutListener(mOnGlobalLayoutListener);
        mCar.disconnect();
    }

    // If no distraction optimization is required in the new restrictions, then dismiss the
    // blocking activity (self).
    private void handleUxRChange(CarUxRestrictions restrictions) {
        if (restrictions == null) {
            return;
        }
        if (!restrictions.isRequiresDistractionOptimization()) {
            finish();
        }
    }

    private void handleCloseApplication() {
        if (isFinishing()) {
            return;
        }

        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(startMain);
        finish();
    }

    private void handleRestartingTask() {
        if (isFinishing()) {
            return;
        }

        // Lock on self to avoid restarting the same task twice.
        synchronized (this) {
            if (Log.isLoggable(CarLog.TAG_AM, Log.INFO)) {
                Log.i(CarLog.TAG_AM, "Restarting task " + mBlockedTaskId);
            }
            CarPackageManager carPm = (CarPackageManager)
                    mCar.getCarManager(Car.PACKAGE_SERVICE);
            carPm.restartTask(mBlockedTaskId);
            finish();
        }
    }
}
