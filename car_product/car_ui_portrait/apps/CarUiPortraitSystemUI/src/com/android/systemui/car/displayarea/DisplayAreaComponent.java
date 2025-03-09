/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package com.android.systemui.car.displayarea;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_REGISTER_CLIENT;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_SYSUI_STARTED;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.caruiportrait.common.service.CarUiPortraitService;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Dagger Subcomponent for DisplayAreas within SysUI.
 */
@SysUISingleton
public class DisplayAreaComponent implements CoreStartable {
    public static final String TAG = "DisplayAreaComponent";
    // action name for the intent when to update the foreground DA visibility
    public static final String DISPLAY_AREA_VISIBILITY_CHANGED =
            "DISPLAY_AREA_VISIBILITY_CHANGED";
    // key name for the intent's extra that tells the DA's visibility status
    public static final String INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE =
            "EXTRA_IS_DISPLAY_AREA_VISIBLE";
    public static final String COLLAPSE_APPLICATION_PANEL =
            "COLLAPSE_APPLICATION_PANEL";

    /**
     * enum to define the state of display area possible.
     * CONTROL_BAR state is when only control bar is visible.
     * FULL state is when display area hosting default apps  cover the screen fully.
     * DEFAULT state where maps are shown above DA for default apps.
     */

    @IntDef({CONTROL_BAR, DEFAULT, FULL, FULL_TO_DEFAULT, DRAGGING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PanelState {}

    public static final int CONTROL_BAR = 0;
    public static final int DEFAULT = 1;
    public static final int FULL = 2;
    public static final int FULL_TO_DEFAULT = 3;
    public static final int DRAGGING = 4;


    private final CarDisplayAreaController mCarDisplayAreaController;
    private final Context mContext;

    /** Messenger for communicating with {@link CarUiPortraitService}. */
    Messenger mService = null;
    /** Flag indicating whether or not {@link CarUiPortraitService} is bounded. */
    boolean mIsBound;

    /**
     * All messages from {@link CarUiPortraitService} are received in this handler.
     */
    final Messenger mMessenger = new Messenger(new Handler());

    /**
     * Class for interacting with the main interface of the {@link CarUiPortraitService}.
     */
    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // Communicating with our service through an IDL interface, so get a client-side
            // representation of that from the raw service object.
            mService = new Messenger(service);

            // Register to the service.
            try {
                Message msg = Message.obtain(null, MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mService.send(msg);
                Message msg1 = Message.obtain(null, MSG_SYSUI_STARTED);
                msg1.replyTo = mMessenger;
                mService.send(msg1);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
                Log.w(TAG, "can't connect to CarUiPortraitService: ", e);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            mService = null;
        }
    };

    void doBindService() {
        // Establish a connection with {@link CarUiPortraitService}. We use an explicit class
        // name because there is no reason to be able to let other applications replace our
        // component.
        // com.android.car.portraitlauncher/com.android.car.caruiportrait.common.service
        // .CarUiPortraitService
        Intent intent = new Intent();
        String pkg = "com.android.car.portraitlauncher";
        String cls = "com.android.car.caruiportrait.common.service.CarUiPortraitService";
        intent.setComponent(new ComponentName(pkg, cls));
        UserHandle user = new UserHandle(ActivityManager.getCurrentUser());
        mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE, user);
        mIsBound = true;
    }

    @Inject
    public DisplayAreaComponent(Context context,
            CarDisplayAreaController carDisplayAreaController) {
        mContext = context;
        mCarDisplayAreaController = carDisplayAreaController;
    }

    private static void logIfDebuggable(String message) {
        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, message);
        }
    }

    @Override
    public void start() {
        logIfDebuggable("start");
        if (CarDisplayAreaUtils.isCustomDisplayPolicyDefined(mContext)) {
            mCarDisplayAreaController.init();
            registerPackageChangeFilter();
        }
        doBindService();
    }

    private void registerPackageChangeFilter() {
        IntentFilter filter = new IntentFilter();
        // add a receiver to listen to ACTION_BOOT_COMPLETED where we will perform tasks that
        // require system to be ready. For example, search list of activities with a specific
        // Intent. This cannot be done while the component is created as that is too early in
        // the lifecycle of system starting and the results returned by package manager is
        // not reliable. So we want to wait until system is ready before we query for list of
        // activities.
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                    logIfDebuggable("on boot complete");
                    mCarDisplayAreaController.updateVoicePlateActivityMap();
                    mCarDisplayAreaController.onBootComplete();
                }
            }
        }, filter, /* broadcastPermission= */ null, /* scheduler= */ null);

        IntentFilter packageChangeFilter = new IntentFilter();
        // add a receiver to listen to ACTION_PACKAGE_ADDED to perform any action when a new
        // application is installed on the system.
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCarDisplayAreaController.updateVoicePlateActivityMap();
            }
        }, packageChangeFilter, null, null);
    }
}
