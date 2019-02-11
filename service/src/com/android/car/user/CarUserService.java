/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.user;

import android.annotation.Nullable;
import android.car.settings.CarSettings;
import android.car.userlib.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * User service for cars. Manages users at boot time. Including:
 *
 * <ol>
 *   <li> Creates a secondary admin user on first run.
 *   <li> Log in to the last active user.
 * <ol/>
 */
public class CarUserService extends BroadcastReceiver implements CarServiceBase {
    private static final String TAG = "CarUserService";
    private final Context mContext;
    private final CarUserManagerHelper mCarUserManagerHelper;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mUser0Unlocked;
    @GuardedBy("mLock")
    private final ArrayList<Runnable> mUser0UnlockTasks = new ArrayList<>();

    public CarUserService(
                @Nullable Context context, @Nullable CarUserManagerHelper carUserManagerHelper) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "constructed");
        }
        mContext = context;
        mCarUserManagerHelper = carUserManagerHelper;
    }

    @Override
    public void init() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "init");
        }
        if (mCarUserManagerHelper.isHeadlessSystemUser()) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            mContext.registerReceiver(this, filter);
        }
    }

    @Override
    public void release() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "release");
        }
        mContext.unregisterReceiver(this);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("Context: " + mContext);
        boolean user0Unlocked;
        synchronized (mLock) {
            user0Unlocked = mUser0Unlocked;
        }
        writer.println("User0Unlocked: " + user0Unlocked);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onReceive " + intent);
        }

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            // We want to set restrictions on system and guest users only once. These are persisted
            // onto disk, so it's sufficient to do it once + we minimize the number of disk writes.
            if (Settings.Global.getInt(mContext.getContentResolver(),
                    CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, /* default= */ 0) == 0) {
                setSystemUserRestrictions();
                mCarUserManagerHelper.initDefaultGuestRestrictions();
                Settings.Global.putInt(mContext.getContentResolver(),
                        CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
            }

        } else if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
            // Update last active user if the switched-to user is a persistent, non-system user.
            final int currentUser = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (currentUser > UserHandle.USER_SYSTEM
                        && mCarUserManagerHelper.isPersistentUser(currentUser)) {
                mCarUserManagerHelper.setLastActiveUser(currentUser);
            }
        }
    }

    /**
     * Set user lock / unlocking status. This is coming from system server through ICar binder call.
     * @param userHandle Handle of user
     * @param unlocked unlocked (=true) or locked (=false)
     */
    public void setUserLockStatus(int userHandle, boolean unlocked) {
        if (userHandle != UserHandle.USER_SYSTEM) {
            return;
        }
        ArrayList<Runnable> tasks = null;
        synchronized (mLock) {
            // Assumes that car service need to do it only once during boot-up
            if (unlocked && !mUser0Unlocked) {
                tasks = new ArrayList<>(mUser0UnlockTasks);
                mUser0UnlockTasks.clear();
                mUser0Unlocked = unlocked;
            }
        }
        if (tasks != null && tasks.size() > 0) {
            Log.d(TAG, "User0 unlocked, run queued tasks:" + tasks.size());
            for (Runnable r : tasks) {
                r.run();
            }
        }
    }

    /**
     * Run give runnable when user 0 is unlocked. If user 0 is already unlocked, it is
     * run inside this call.
     * @param r Runnable to run.
     */
    public void runOnUser0Unlock(Runnable r) {
        boolean runNow = false;
        synchronized (mLock) {
            if (mUser0Unlocked) {
                runNow = true;
            } else {
                mUser0UnlockTasks.add(r);
            }
        }
        if (runNow) {
            r.run();
        }
    }

    private void setSystemUserRestrictions() {
        // Disable adding accounts for system user.
        mCarUserManagerHelper.setUserRestriction(mCarUserManagerHelper.getSystemUserInfo(),
                UserManager.DISALLOW_MODIFY_ACCOUNTS, /* enable= */ true);

        // Disable Location service for system user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        locationManager.setLocationEnabledForUser(
                /* enabled= */ false, UserHandle.of(UserHandle.USER_SYSTEM));
    }
}
