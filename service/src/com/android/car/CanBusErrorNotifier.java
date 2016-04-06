/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * Class used to notify user about CAN bus failure.
 */
final class CanBusErrorNotifier {
    private static final String TAG = CarLog.TAG_CAN_BUS + ".ERROR.NOTIFIER";
    private static final int NOTIFICATION_ID = 1;
    private static final boolean IS_RELEASE_BUILD = "user".equals(Build.TYPE);

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    @GuardedBy("this")
    private boolean mFailed;

    @GuardedBy("this")
    private Notification mNotification;

    CanBusErrorNotifier(Context context) {
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mContext = context;
    }

    public void setCanBusFailure(boolean failed) {
        synchronized (this) {
            if (mFailed == failed) {
                return;
            }
            mFailed = failed;
        }
        Log.i(TAG, "Changing CAN bus failure state to " + failed);

        if (failed) {
            showNotification();
        } else {
            hideNotification();
        }
    }

    private void showNotification() {
        if (IS_RELEASE_BUILD) {
            // TODO: for release build we should show message to take car to the dealer.
            return;
        }
        Notification notification;
        synchronized (this) {
            if (mNotification == null) {
                mNotification = new Notification.Builder(mContext)
                        .setContentTitle(mContext.getString(R.string.car_can_bus_failure))
                        .setContentText(mContext.getString(R.string.car_can_bus_failure_desc))
                        .setSmallIcon(R.drawable.car_ic_error)
                        .setOngoing(true)
                        .build();
            }
            notification = mNotification;
        }
        mNotificationManager.notify(TAG, NOTIFICATION_ID, notification);
    }

    private void hideNotification() {
        if (IS_RELEASE_BUILD) {
            // TODO: for release build we should show message to take car to the dealer.
            return;
        }
        mNotificationManager.cancel(TAG, NOTIFICATION_ID);
    }
}
