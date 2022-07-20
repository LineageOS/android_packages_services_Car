/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_MULTI_WINDOW;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaOrganizer;

import com.android.systemui.R;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;

/**
 * Controls the bounds of the home background, audio bar and application displays. This is a
 * singleton class as there should be one controller used to register and control the DA's
 */
public class CarDisplayAreaController implements ConfigurationController.ConfigurationListener,
        CommandQueue.Callbacks {

    static final int FEATURE_VOICE_PLATE = FEATURE_VENDOR_FIRST + 6;
    private static final String TAG = "CarDisplayAreaController";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private final DisplayAreaOrganizer mOrganizer;
    private final CarFullscreenTaskListener mCarFullscreenTaskListener;

    private final ShellExecutor mShellExecutor;

    private final ComponentName mNotificationCenterComponent;
    private final Context mApplicationContext;
    // contains the list of activities that will be displayed on feature {@link
    // CarDisplayAreaOrganizer.FEATURE_VOICE_PLATE)
    private final Set<ComponentName> mVoicePlateActivitySet;
    private final CarServiceProvider mCarServiceProvider;
    // true if there are activities still pending to be mapped to the voice plate DA as
    // Car object was not created.
    private boolean mIsPendingVoicePlateActivityMappingToDA;
    private DisplayAreaAppearedInfo mVoicePlateDisplay;
    private final TaskStackListener mOnActivityRestartAttemptListener = new TaskStackListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible)
                throws RemoteException {
            super.onActivityRestartAttempt(task, homeTaskVisible, clearedTask, wasVisible);
            logIfDebuggable("onActivityRestartAttempt: " + task);
            updateForegroundDaVisibility(task);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            super.onTaskMovedToFront(taskInfo);
            logIfDebuggable("onTaskMovedToFront: " + taskInfo);
            updateForegroundDaVisibility(taskInfo);
        }
    };
    private final CarFullscreenTaskListener.OnTaskChangeListener mOnTaskChangeListener =
            new CarFullscreenTaskListener.OnTaskChangeListener() {
                @Override
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
                    logIfDebuggable("onTaskAppeared: " + taskInfo);
                    updateForegroundDaVisibility(taskInfo);
                }

                @Override
                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    Log.e(TAG, " onTaskVanished: " + taskInfo);

                    if (taskInfo.displayAreaFeatureId == FEATURE_VOICE_PLATE) {
                        resetVoicePlateDisplayArea();
                    }
                }
            };
    private Car mCar;

    /**
     * Initializes the controller
     */
    @Inject
    public CarDisplayAreaController(Context applicationContext,
            CarFullscreenTaskListener carFullscreenTaskListener,
            ShellExecutor shellExecutor,
            CarServiceProvider carServiceProvider,
            DisplayAreaOrganizer organizer,
            CommandQueue commandQueue) {
        mApplicationContext = applicationContext;
        mOrganizer = organizer;
        mShellExecutor = shellExecutor;
        mCarServiceProvider = carServiceProvider;
        mCarFullscreenTaskListener = carFullscreenTaskListener;
        Resources resources = applicationContext.getResources();
        mNotificationCenterComponent = ComponentName.unflattenFromString(resources.getString(
                R.string.config_notificationCenterActivity));
        mVoicePlateActivitySet = new ArraySet<>();

        commandQueue.addCallback(this);

    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        Intent homeActivityIntent = new Intent(Intent.ACTION_MAIN);
        homeActivityIntent.addCategory(Intent.CATEGORY_HOME);
        homeActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mApplicationContext.startActivityAsUser(homeActivityIntent, UserHandle.CURRENT);
    }

    private static void logIfDebuggable(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    @Override
    public void animateExpandNotificationsPanel() {
        Intent intent = new Intent();
        intent.setComponent(mNotificationCenterComponent);
        mApplicationContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    /** Registers the DA organizer. */
    public void register() {
        logIfDebuggable("register organizer and set default bounds");

        ShellTaskOrganizer taskOrganizer = new ShellTaskOrganizer(mShellExecutor);
        taskOrganizer.addListenerForType(mCarFullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        // Use the same TaskListener for MULTI_WINDOW windowing mode as there is nothing that has
        // to be done differently. This is because the tasks are still running in 'fullscreen'
        // within a DisplayArea.
        taskOrganizer.addListenerForType(mCarFullscreenTaskListener,
                TASK_LISTENER_TYPE_MULTI_WINDOW);

        taskOrganizer.registerOrganizer();
        // Register DA organizer.
        registerOrganizer();

        mCarServiceProvider.addListener(car -> {
            mCar = car;
            if (mIsPendingVoicePlateActivityMappingToDA) {
                mIsPendingVoicePlateActivityMappingToDA = false;
                updateVoicePlateActivityMap();
            }
        });

        ActivityTaskManager.getInstance().registerTaskStackListener(
                mOnActivityRestartAttemptListener);
        // add CarFullscreenTaskListener to control the foreground DA when the task appears.
        mCarFullscreenTaskListener.registerOnTaskChangeListener(mOnTaskChangeListener);
    }

    void updateVoicePlateActivityMap() {
        Context currentUserContext = mApplicationContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /* flags= */ 0);

        Intent voiceIntent = new Intent(Intent.ACTION_VOICE_ASSIST, /* uri= */ null);
        List<ResolveInfo> result = currentUserContext.getPackageManager().queryIntentActivities(
                voiceIntent, PackageManager.MATCH_ALL);
        if (!result.isEmpty() && mCar == null) {
            mIsPendingVoicePlateActivityMappingToDA = true;
            return;
        } else if (result.isEmpty()) {
            return;
        }

        CarActivityManager carAm = (CarActivityManager) mCar.getCarManager(
                Car.CAR_ACTIVITY_SERVICE);
        for (ResolveInfo info : result) {
            if (mVoicePlateActivitySet.add(info.activityInfo.getComponentName())) {
                logIfDebuggable("adding the following component to voice plate: "
                        + info.activityInfo.getComponentName());
                CarDisplayAreaUtils.setPersistentActivity(carAm,
                        info.activityInfo.getComponentName(),
                        FEATURE_VOICE_PLATE, "VoicePlate");
            }
        }
    }

    private void updateForegroundDaVisibility(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.baseIntent == null || taskInfo.baseIntent.getComponent() == null
        ) {
            return;
        }

        ComponentName componentName = taskInfo.baseIntent.getComponent();

        // Voice plate will be shown as the top most layer. Also, we don't want to change the
        // state of the DA's when voice plate is shown.
        boolean isVoicePlate = mVoicePlateActivitySet.contains(componentName);
        if (isVoicePlate) {
            showVoicePlateDisplayArea();
        }
    }

    void showVoicePlateDisplayArea() {
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.show(mVoicePlateDisplay.getLeash());
        tx.apply(true);
    }

    void resetVoicePlateDisplayArea() {
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.hide(mVoicePlateDisplay.getLeash());
        tx.apply(true);
    }

    /** Registers DA organizer. */
    private void registerOrganizer() {
        List<DisplayAreaAppearedInfo> voicePlateDisplayAreaInfo =
                mOrganizer.registerOrganizer(FEATURE_VOICE_PLATE);
        if (voicePlateDisplayAreaInfo.size() != 1) {
            throw new IllegalStateException("Can't find display to launch voice plate");
        }

        // As we have only 1 display defined for each display area feature get the 0th index.
        mVoicePlateDisplay = voicePlateDisplayAreaInfo.get(0);

        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.hide(mVoicePlateDisplay.getLeash());
        tx.apply(true);
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.unregisterOrganizer();
        mVoicePlateDisplay = null;
        ActivityTaskManager.getInstance()
                .unregisterTaskStackListener(mOnActivityRestartAttemptListener);
    }
}
