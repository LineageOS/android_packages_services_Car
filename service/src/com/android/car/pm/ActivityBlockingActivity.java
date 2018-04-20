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

import android.app.Activity;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.TextView;

import com.android.car.CarLog;
import com.android.car.R;

/**
 * Default activity that will be launched when the current foreground activity is not allowed.
 * Additional information on blocked Activity will be passed as extra in Intent
 * via {@link #INTENT_KEY_BLOCKED_ACTIVITY} key.
 */
public class ActivityBlockingActivity extends Activity {
    public static final String INTENT_KEY_BLOCKED_ACTIVITY = "blocked_activity";

    private Car mCar;
    private CarUxRestrictionsManager mUxRManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocking);

        String blockedActivity = getIntent().getStringExtra(INTENT_KEY_BLOCKED_ACTIVITY);

        TextView blockedTitle = findViewById(R.id.activity_blocked_title);
        blockedTitle.setText(getString(R.string.activity_blocked_string,
                findBlockedApplicationLabel(blockedActivity)));

        // Listen to the CarUxRestrictions so this blocking activity can be dismissed when the
        // restrictions are lifted.
        mCar = Car.createCar(this, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    mUxRManager = (CarUxRestrictionsManager) mCar.getCarManager(
                            Car.CAR_UX_RESTRICTION_SERVICE);
                    // This activity would have been launched only in a restricted state.
                    // But ensuring when the service connection is established, that we are still
                    // in a restricted state.
                    handleUxRChange(mUxRManager.getCurrentCarUxRestrictions());
                    mUxRManager.registerListener(ActivityBlockingActivity.this::handleUxRChange);
                } catch (CarNotConnectedException e) {
                    Log.e(CarLog.TAG_AM, "Failed to get CarUxRestrictionsManager", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                finish();
                mUxRManager = null;
            }
        });
        mCar.connect();
    }

    /**
     * Returns the application label of blockedActivity. If that fails, the original activity will
     * be returned.
     */
    private String findBlockedApplicationLabel(String blockedActivity) {
        String label = blockedActivity;
        // Attempt to update blockedActivity name to application label.
        try {
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(
                    ComponentName.unflattenFromString(blockedActivity).getPackageName(), 0);
            CharSequence appLabel = getPackageManager().getApplicationLabel(applicationInfo);
            if (appLabel != null) {
                label = appLabel.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return label;
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCar.isConnected() && mUxRManager != null) {
            try {
                mUxRManager.unregisterListener();
            } catch (CarNotConnectedException e) {
                Log.e(CarLog.TAG_AM, "Cannot unregisterListener", e);
            }
            mUxRManager = null;
            mCar.disconnect();
        }
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
}
