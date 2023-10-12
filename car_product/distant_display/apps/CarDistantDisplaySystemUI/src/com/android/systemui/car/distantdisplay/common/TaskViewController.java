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
package com.android.systemui.car.distantdisplay.common;

import static com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver.MOVE_ACTION;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.app.CarTaskViewController;
import android.car.app.CarTaskViewControllerCallback;
import android.car.app.CarTaskViewControllerHostLifecycle;
import android.car.app.RemoteCarDefaultRootTaskView;
import android.car.app.RemoteCarDefaultRootTaskViewCallback;
import android.car.app.RemoteCarDefaultRootTaskViewConfig;
import android.car.app.RemoteCarRootTaskView;
import android.car.app.RemoteCarRootTaskViewCallback;
import android.car.app.RemoteCarRootTaskViewConfig;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver;
import com.android.systemui.car.distantdisplay.activity.NavigationTaskViewWallpaperActivity;
import com.android.systemui.car.distantdisplay.activity.RootTaskViewWallpaperActivity;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.HashSet;
import java.util.Set;

/**
 * TaskView Controller that manages the implementation details for task views on a distant display.
 * <p>
 * This is also a common class used between two different technical implementation where in one
 * TaskViews are hosted in a window created by systemUI Vs in another where TaskView are created
 * via an activity.
 */
public class TaskViewController {
    public static final String TAG = TaskViewController.class.getSimpleName();

    private static final boolean DEBUG = Build.IS_ENG || Build.IS_USERDEBUG;
    private static final int DEFAULT_DISPLAY_ID = 0;

    private final UserUnlockReceiver mUserUnlockReceiver = new UserUnlockReceiver();
    private final Context mContext;
    private Intent mBaseIntentForTopTask;

    private RemoteCarDefaultRootTaskView mDefaultRootTaskView = null;
    private Intent mBaseIntentForTopTaskDD;
    private FrameLayout mNavigationView;
    private boolean mNavigationTaskViewInitialized;
    private boolean mRootTaskViewInitialized;
    private FrameLayout mRootView;
    private CarTaskViewControllerHostLifecycle mCarTaskViewControllerHostLifecycle;
    private int mDistantDisplayId;
    private CarTaskViewController mCarTaskViewController;
    private Set<ComponentName> mNavigationActivities;
    private boolean mIsUserUnlocked;

    private final TaskStackChangeListener mTaskStackChangeLister = new TaskStackChangeListener() {

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            if (DEBUG) {
                Log.d(TAG, "onTaskMovedToFront: " + taskInfo
                        + " token: " + taskInfo.token + " displayId: " + taskInfo.displayId);
            }

            if (taskInfo.displayId == DEFAULT_DISPLAY_ID) {
                mBaseIntentForTopTask = taskInfo.baseIntent;
            } else if (taskInfo.displayId == mDistantDisplayId) {
                mBaseIntentForTopTaskDD = taskInfo.baseIntent;
            }
        }
    };

    public TaskViewController(Context context) {
        mContext = context;
    }

    /**
     * Returns the resource id for the layout that acts as the container of the taskViews.
     */
    public static int getTaskViewContainerLayoutResId() {
        return R.layout.car_ui_distant_display_taskview_container;
    }

    private static ArraySet<ComponentName> convertToComponentNames(String[] componentStrings) {
        ArraySet<ComponentName> componentNames = new ArraySet<>(componentStrings.length);
        for (int i = componentStrings.length - 1; i >= 0; i--) {
            componentNames.add(ComponentName.unflattenFromString(componentStrings[i]));
        }
        return componentNames;
    }

    /**
     * Initializes and setups the TaskViews. This method accepts a container with taskviews and
     * then attaches the task view to it. There are 2 task views created
     * {@link RemoteCarDefaultRootTaskView} and {@link RemoteCarRootTaskView} for navigation space.
     */
    public void initialize(ViewGroup parent, boolean isUserUnlocked) {
        mNavigationView = parent.findViewById(R.id.navigationView);
        mRootView = parent.findViewById(R.id.rootView);
        mIsUserUnlocked = isUserUnlocked;

        mDistantDisplayId = mContext.getResources().getInteger(R.integer.distant_display_id);

        mNavigationActivities = new HashSet<>(convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_navigationActivities)));

        mayRegisterUserUnlockReceiver();
        mCarTaskViewControllerHostLifecycle = new CarTaskViewControllerHostLifecycle();
        mCarTaskViewControllerHostLifecycle.hostAppeared();

        setupRemoteCarTaskView();

        if (DEBUG) {
            setupDebuggingThroughAdb();
        }
    }

    private void setupDebuggingThroughAdb() {
        IntentFilter filter = new IntentFilter(MOVE_ACTION);
        MoveTaskReceiver receiver = new MoveTaskReceiver();
        receiver.registerOnChangeDisplayForTask(this::changeDisplayForTask);
        ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void changeDisplayForTask(int oldDisplayId, int newDisplayId) {
        if (!((oldDisplayId == DEFAULT_DISPLAY_ID && newDisplayId == mDistantDisplayId)
                || (oldDisplayId == mDistantDisplayId && newDisplayId == DEFAULT_DISPLAY_ID))) {
            Log.w(TAG, "moving task not supported from display " + oldDisplayId + " to "
                    + newDisplayId);
            return;
        }
        if (newDisplayId == DEFAULT_DISPLAY_ID && mBaseIntentForTopTaskDD != null) {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
            options.setLaunchDisplayId(DEFAULT_DISPLAY_ID);
            mContext.startActivityAsUser(mBaseIntentForTopTaskDD, options.toBundle(),
                    UserHandle.CURRENT);
        } else if (newDisplayId == mDistantDisplayId) {
            ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
            options.setLaunchDisplayId(newDisplayId);
            mContext.startActivityAsUser(mBaseIntentForTopTask, options.toBundle(),
                    UserHandle.CURRENT);
        }
    }

    private void setupRemoteCarTaskView() {
        Car.createCar(/* context= */ mContext, /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        Log.w(TAG, "CarService is not ready.");
                        return;
                    }
                    CarActivityManager carAM = (CarActivityManager) car.getCarManager(
                            Car.CAR_ACTIVITY_SERVICE);

                    carAM.getCarTaskViewController(
                            mContext,
                            mCarTaskViewControllerHostLifecycle,
                            mContext.getMainExecutor(),
                            new CarTaskViewControllerCallback() {
                                @Override
                                public void onConnected(
                                        CarTaskViewController carTaskViewController) {
                                    mCarTaskViewController = carTaskViewController;
                                    taskViewControllerReady();
                                }

                                @Override
                                public void onDisconnected(
                                        CarTaskViewController carTaskViewController) {
                                }
                            });
                });

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackChangeLister);
    }

    private void mayRegisterUserUnlockReceiver() {
        if (mIsUserUnlocked) {
            launchWallpaperActivities();
            return;
        }
        mUserUnlockReceiver.register(mContext, this::onUserUnlocked);
    }

    private void onUserUnlocked() {
        mIsUserUnlocked = true;
        launchWallpaperActivities();
    }

    private void launchWallpaperActivities() {
        if (!isSystemReady()) {
            return;
        }
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        options.setLaunchDisplayId(mDistantDisplayId);
        mContext.startActivityAsUser(
                RootTaskViewWallpaperActivity.createIntent(mContext),
                options.toBundle(), UserHandle.CURRENT);
        mContext.startActivityAsUser(
                NavigationTaskViewWallpaperActivity.createIntent(mContext),
                options.toBundle(), UserHandle.CURRENT);
    }

    private boolean isSystemReady() {
        return mIsUserUnlocked && mRootTaskViewInitialized && mNavigationTaskViewInitialized;
    }

    private void taskViewControllerReady() {
        mCarTaskViewController.createRemoteCarDefaultRootTaskView(
                new RemoteCarDefaultRootTaskViewConfig.Builder()
                        .setDisplayId(mDistantDisplayId)
                        .embedHomeTask(true)
                        .embedRecentsTask(true)
                        .build(),
                mContext.getMainExecutor(),
                new RemoteCarDefaultRootTaskViewCallback() {
                    @Override
                    public void onTaskViewCreated(@NonNull RemoteCarDefaultRootTaskView taskView) {
                        Log.d(TAG, "default Root Task View is created: " + taskView);
                        if (mDefaultRootTaskView == null) {
                            mDefaultRootTaskView = taskView;
                        }
                        taskView.setZOrderMediaOverlay(true);
                        mRootView.addView(taskView);
                        taskView.updateWindowBounds();
                    }

                    @Override
                    public void onTaskViewInitialized() {
                        Log.d(TAG, "Default Root Task View is ready");
                        mRootTaskViewInitialized = true;
                        launchWallpaperActivities();
                    }
                }
        );

        mCarTaskViewController.createRemoteCarRootTaskView(
                new RemoteCarRootTaskViewConfig.Builder()
                        .setDisplayId(mDistantDisplayId)
                        .setAllowListedActivities(mNavigationActivities.stream().toList())
                        .build(),
                mContext.getMainExecutor(),
                new RemoteCarRootTaskViewCallback() {
                    @Override
                    public void onTaskViewCreated(@NonNull RemoteCarRootTaskView taskView) {
                        Log.d(TAG, "Root Task View is created");
                        taskView.setZOrderMediaOverlay(true);
                        mNavigationView.addView(taskView);
                    }

                    @Override
                    public void onTaskViewInitialized() {
                        Log.d(TAG, "Root Task View is ready");
                        mNavigationTaskViewInitialized = true;
                        launchWallpaperActivities();
                    }
                }
        );
    }
}
