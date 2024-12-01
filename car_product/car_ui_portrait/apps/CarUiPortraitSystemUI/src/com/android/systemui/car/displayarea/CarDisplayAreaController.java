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

import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_IME_PLACEHOLDER;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.KEY_ROOT_DISPLAY_AREA_ID;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_COLLAPSE_APPLICATION_PANEL;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_CONTROL_BAR_HEIGHT_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_IS_APPLICATION_PANEL_OPEN;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_LAUNCHER_READY;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_TOP_TASK_IN_APPLICATION_PANEL;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.REQUEST_FROM_LAUNCHER;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.REQUEST_FROM_SYSTEM_UI;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.BACKGROUND_TASK_CONTAINER;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.CONTROL_BAR_DISPLAY_AREA;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.FEATURE_TASKDISPLAYAREA_PARENT;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.FEATURE_TITLE_BAR;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.FEATURE_VOICE_PLATE;
import static com.android.systemui.car.displayarea.CarDisplayAreaOrganizer.FOREGROUND_DISPLAY_AREA_ROOT;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.COLLAPSE_APPLICATION_PANEL;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.CONTROL_BAR;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.DEFAULT;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.DISPLAY_AREA_VISIBILITY_CHANGED;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.DRAGGING;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.FULL;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.FULL_TO_DEFAULT;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.app.UiModeManager;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.internal.app.AssistUtils;
import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.displayarea.CarDisplayAreaAnimationController.CarDisplayAreaTransitionAnimator;
import com.android.systemui.car.displayarea.DisplayAreaComponent.PanelState;
import com.android.systemui.car.loading.LoadingViewController;
import com.android.systemui.car.wm.CarFullscreenTaskMonitorListener;
import com.android.systemui.qs.QSHost;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.wm.CarUiPortraitDisplaySystemBarsController;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;

/**
 * Controls the bounds of the home background, audio bar and application displays. This is a
 * singleton class as there should be one controller used to register and control the DA's
 */
public class CarDisplayAreaController implements ConfigurationController.ConfigurationListener,
        CommandQueue.Callbacks {

    // Layer index of how display areas should be placed. Keeping a gap of 100 if we want to
    // add some other display area layers in between in the future.
    static final int BACKGROUND_LAYER_INDEX = 100;
    static final int FOREGROUND_LAYER_INDEX = BACKGROUND_LAYER_INDEX + 1;
    static final int CONTROL_BAR_LAYER_INDEX = BACKGROUND_LAYER_INDEX + 2;
    static final int VOICE_PLATE_LAYER_SHOWN_INDEX = BACKGROUND_LAYER_INDEX + 3;
    static final int TITLE_BAR_LAYER_INDEX = BACKGROUND_LAYER_INDEX + 10;

    private static final String TAG = CarDisplayAreaController.class.getSimpleName();
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final int TITLE_BAR_WINDOW_TYPE =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private final CarDisplayAreaOrganizer mOrganizer;
    private final FullscreenTaskListener mFullscreenTaskMonitorListener;
    private final ShellExecutor mShellExecutor;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final CarUiPortraitDisplaySystemBarsController mCarUiDisplaySystemBarsController;
    private final ComponentName mNotificationCenterComponent;
    private final Context mApplicationContext;
    private final CarServiceProvider mCarServiceProvider;
    private final UiModeManager mUiModeManager;
    private final int mTitleBarDragThreshold;
    private final CarDisplayAreaTouchHandler mCarDisplayAreaTouchHandler;
    // height of DA hosting the control bar.
    private int mControlBarDisplayHeight;
    private final int mDpiDensity;
    private final int mTotalScreenWidth;
    // height of DA hosting default apps and covering the maps fully.
    private final int mFullDisplayHeight;
    // height of DA hosting default apps and covering the maps to default height.
    private final int mDefaultDisplayHeight;
    private final int mTitleBarHeight;
    private final int mTitleBarViewHeight;
    private final int mScreenHeightWithoutNavBar;
    private final int mTotalScreenHeight;
    private final Rect mControlBarDisplayBounds = new Rect();
    private final Rect mForegroundApplicationDisplayBounds = new Rect();
    private final Rect mForegroundApplicationDisplayBoundsInvisible = new Rect();
    private final Rect mForegroundApplicationDisplayBoundsFull = new Rect();
    private final Rect mTitleBarDisplayBounds = new Rect();
    private final Rect mVoicePlateDisplayBounds = new Rect();
    private final Rect mBackgroundApplicationDisplayBounds = new Rect();
    private final Rect mVisibleBackgroundBounds = new Rect();
    private final Rect mImeBounds = new Rect();
    private final Rect mNavBarBounds = new Rect();
    private final SyncTransactionQueue mSyncQueue;
    private final IBinder mWindowToken = new Binder();
    private final IApplicationThread mAppThread =
            ActivityThread.currentActivityThread().getApplicationThread();
    private final HashMap<String, Boolean> mForegroundDAComponentsVisibilityMap;
    private final ConfigurationController mConfigurationController;
    private final int mNavBarHeight;
    private final int mStatusBarHeight;
    private final CarDisplayAreaTransitions mCarDisplayAreaTransitions;
    private boolean mIsNotificationCenterOnTop;
    private LoadingViewController mLoadingViewController;
    private HashSet<Integer> mActiveTasksOnForegroundDA;
    private HashSet<Integer> mActiveTasksOnBackgroundDA;
    private boolean mIsForegroundAppRequestingImmersiveMode;
    private boolean mIsControlBarDisplayAreaEmpty;
    private @PanelState int mCurrentForegroundDaState = CONTROL_BAR;
    private boolean mIsForegroundDaVisible;
    private boolean mIsHostingDefaultApplicationDisplayAreaVisible;
    private int mEnterExitAnimationDurationMs;
    private boolean mIsForegroundDaFullScreen;
    private AssistUtils mAssistUtils;
    private int mControlBarTaskId = -1;
    private boolean mIsUiModeNight;
    private DisplayAreaAppearedInfo mForegroundApplicationsDisplay;
    private DisplayAreaAppearedInfo mTitleBarDisplay;
    private DisplayAreaAppearedInfo mVoicePlateDisplay;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private DisplayAreaAppearedInfo mControlBarDisplay;
    private DisplayAreaAppearedInfo mImeContainerDisplayArea;
    private Car mCar;
    private boolean mIsUserSetupInProgress;
    private boolean mOnBootCompleteCalled;
    private Configuration mConfiguration = new Configuration();
    private View mGripBar;
    private View mImmersiveToolBar;
    private TaskCategoryManager mTaskCategoryManager;
    private View mTitleBarView;
    private WindowManager mTitleBarWindowManager;
    private DisplayAreaAppearedInfo mDefaultAppDisplay;
    private Rect mDefaultAppDisplayBounds = new Rect();
    private Rect mTitleBarViewBounds = new Rect();
    private ActivityManager.RunningTaskInfo mCurrentForegroundTask;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;

    /**
     * The WindowContext that is registered with {@link #mTitleBarWindowManager} with options to
     * specify the {@link RootDisplayArea} to attach the confirmation window.
     */
    @Nullable
    private Context mTitleBarWindowContext;
    private boolean mIsDisplayAreaReady = false;
    private boolean mSkipNextPanelAction = false;

    private final CarUiPortraitDisplaySystemBarsController.Callback
            mSystemBarControllerCallback =
            new CarUiPortraitDisplaySystemBarsController.Callback() {
                @Override
                public void onImmersiveRequestedChanged(ComponentName componentName,
                        boolean requested) {
                    logIfDebuggable("onImmersiveRequestedChanged " + componentName + " requested"
                            + requested + ", current foreground task is" + mCurrentForegroundTask);

                    // If the requesting application is a voice plate, background, or ignored
                    // package, ignore immersive requests.
                    if (mTaskCategoryManager.isFullScreenActivity(componentName)) {
                        return;
                    }
                    if (mTaskCategoryManager.isBackgroundApp(componentName)) {
                        return;
                    }

                    if (mTaskCategoryManager.isControlBar(componentName)) {
                        return;
                    }

                    if (mTaskCategoryManager.shouldIgnoreForApplicationPanel(componentName)) {
                        return;
                    }

                    ComponentName visibleComponent = mTaskCategoryManager.getVisibleActivity(
                            mCurrentForegroundTask);
                    if (visibleComponent != null && !visibleComponent.equals(componentName)) {
                        return;
                    }

                    mIsForegroundAppRequestingImmersiveMode = requested;
                    setImmersive(mIsForegroundAppRequestingImmersiveMode);
                    mCarUiDisplaySystemBarsController.requestImmersiveMode(
                            mApplicationContext.getDisplayId(),
                            mIsForegroundAppRequestingImmersiveMode
                                    ? WindowInsets.Type.navigationBars()
                                    : WindowInsets.Type.systemBars());
                }

                @Override
                public void onImmersiveStateChanged(boolean immersive) {
                    logIfDebuggable("onImmersiveStateChanged " + immersive);
                }
            };

    // TODO(b/380944885): deprecate and use CarDisplayAreaTransitions
    private final TaskStackListener mOnActivityRestartAttemptListener = new TaskStackListener() {

        /**
         * Called whenever IActivityManager.startActivity is called on an activity that is already
         * running, but the task is either brought to the front or a new Intent is delivered to it.
         *
         * @param task information about the task the activity was relaunched into
         * @param homeTaskVisible whether or not the home task is visible
         * @param clearedTask whether or not the launch activity also cleared the task as a part of
         * starting
         * @param wasVisible whether the activity was visible before the restart attempt
         */
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible)
                throws RemoteException {
            super.onActivityRestartAttempt(task, homeTaskVisible, clearedTask, wasVisible);
            logIfDebuggable("onActivityRestartAttempt: " + task);

            if (!shouldTaskShowOnForegroundDA(task)) {
                logIfDebuggable("Should not show in foreground da");
                return;
            }

            mCurrentForegroundTask = task;
            if (mTaskCategoryManager.isAppGridActivity(task) && mSkipNextPanelAction) {
                logIfDebuggable("skip appgrid move to front due to flag");
                mSkipNextPanelAction = false;
                return;
            }
            updateApplicationPanel(task);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            super.onTaskMovedToFront(taskInfo);

            if (!shouldTaskShowOnForegroundDA(taskInfo)) {
                logIfDebuggable("Should not show in foreground da");
                return;
            }

            logIfDebuggable("onTaskMovedToFront: " + taskInfo);
            mCurrentForegroundTask = taskInfo;
            if (mTaskCategoryManager.isAppGridActivity(taskInfo)) {
                logIfDebuggable("skip appgrid move to front");
                return;
            }
            updateApplicationPanel(taskInfo);
        }

        @Override
        public void onTaskRemoved(int taskId) throws RemoteException {
            super.onTaskRemoved(taskId);
            Log.e(TAG, " onTaskRemoved: " + taskId);
            if (mActiveTasksOnBackgroundDA != null
                    && mActiveTasksOnBackgroundDA.remove(taskId)) {
                logIfDebuggable("removed task " + taskId
                        + " from background DA, total tasks left: "
                        + mActiveTasksOnBackgroundDA.size());
            }

            if (mActiveTasksOnBackgroundDA != null && mActiveTasksOnBackgroundDA.isEmpty()) {
                logIfDebuggable("Background panel is empty");
            }

            if (mIsControlBarDisplayAreaEmpty && taskId == mControlBarTaskId) {
                logIfDebuggable("Control bar panel is empty");
            }
        }
    };
    private final CarFullscreenTaskMonitorListener.OnTaskChangeListener mOnTaskChangeListener =
            new CarFullscreenTaskMonitorListener.OnTaskChangeListener() {
                @Override
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
                    ComponentName componentName = null;
                    if (taskInfo.baseIntent != null) {
                        componentName = taskInfo.baseIntent.getComponent();
                    }

                    boolean isBackgroundApp = mTaskCategoryManager.isBackgroundApp(componentName);
                    if (isBackgroundApp) {
                        addActiveTaskToBackgroundDAMap(taskInfo.taskId);
                    }

                    boolean isControlBarApp = mTaskCategoryManager.isControlBar(componentName);
                    if (isControlBarApp) {
                        mIsControlBarDisplayAreaEmpty = false;
                    }

                    boolean isVoicePlate = mTaskCategoryManager.isFullScreenActivity(componentName);
                    if (isVoicePlate) {
                        showVoicePlateDisplayArea();
                    }
                }

                @Override
                public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    if (mCurrentForegroundTask != null && taskInfo != null
                            && taskInfo.taskId == mCurrentForegroundTask.taskId) {
                        mCurrentForegroundTask = taskInfo;
                    }
                }

                @Override
                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    if (mActiveTasksOnBackgroundDA != null
                            && mActiveTasksOnBackgroundDA.remove(taskInfo.taskId)) {
                        logIfDebuggable("removed task " + taskInfo.taskId
                                + " from background DA, total tasks: "
                                + mActiveTasksOnBackgroundDA.size());
                    }

                    if (taskInfo.displayAreaFeatureId == FEATURE_VOICE_PLATE) {
                        resetVoicePlateDisplayArea();
                    }

                    if (mActiveTasksOnForegroundDA == null) {
                        return;
                    }

                    if (mActiveTasksOnForegroundDA.remove(taskInfo.taskId)) {
                        logIfDebuggable("removed task " + taskInfo.taskId
                                + " from foreground DA, total tasks: "
                                + mActiveTasksOnForegroundDA.size());
                    }
                }
            };
    private final CarDeviceProvisionedListener mCarDeviceProvisionedListener =
            new CarDeviceProvisionedListener() {
                @Override
                public void onUserSetupInProgressChanged() {
                    updateUserSetupState();
                }
            };

    CarDisplayAreaTouchHandler.OnDragDisplayAreaListener mOnDragDisplayAreaListener =
            new CarDisplayAreaTouchHandler.OnDragDisplayAreaListener() {
                float mCurrentPos = -1;

                @Override
                public void onStart(float x, float y) {
                    mCurrentPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                            - mControlBarDisplayHeight;
                }

                @Override
                public void onMove(float x, float y) {
                    if (mIsForegroundAppRequestingImmersiveMode) {
                        return;
                    }
                    if (y <= mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                            - mControlBarDisplayHeight) {
                        return;
                    }
                    animateToDraggingState((int) mCurrentPos, (int) y, 0);
                    mCurrentPos = y;
                }

                @Override
                public void onFinish(float x, float y) {
                    if (mIsForegroundAppRequestingImmersiveMode) {
                        return;
                    }
                    logIfDebuggable("onFinish to x " + x + ", y" + y);

                    if (y >= mTitleBarDragThreshold) {
                        animateToControlBarState((int) y,
                                mScreenHeightWithoutNavBar + mTitleBarHeight, 0);
                        mCarDisplayAreaTouchHandler.updateTitleBarVisibility(false);
                    } else {
                        animateToDefaultState((int) y,
                                mVisibleBackgroundBounds.bottom, 0);
                    }
                }
            };


    private final CarDisplayAreaAnimationCallback mControlBarStateCallback =
            new CarDisplayAreaAnimationCallback() {
                @Override
                public void onAnimationEnd(SurfaceControl.Transaction tx,
                        CarDisplayAreaTransitionAnimator animator) {
                    logIfDebuggable("on control animation end");
                    if (mOnBootCompleteCalled) {
                        mOnBootCompleteCalled = false;
                        logIfDebuggable("Do not block next appgrid press");
                        mSkipNextPanelAction = false;
                    } else {
                        launchAppGrid(true);
                    }
                    broadcastForegroundDAVisibilityChange(false);

                    SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                    setToDefaultLayers(transaction);
                    transaction.apply();
                }
            };

    private final CarUxRestrictionsManager.OnUxRestrictionsChangedListener mUxRestrictionsListener =
            this::handleUxRestrictionsChange;

    private final CarDisplayAreaTransitions.Callback mCarDisplayAreaTransitionsCallback =
            new CarDisplayAreaTransitions.Callback() {
                @Override
                public void onStartAnimation(@NonNull SurfaceControl.Transaction startTransaction,
                        @NonNull SurfaceControl.Transaction finishTransaction) {
                    setToDefaultLayers(startTransaction);
                    setToDefaultLayers(finishTransaction);
                }

                @Override
                public void onHandleRequest(WindowContainerTransaction wct,
                        @NonNull TransitionRequestInfo request) {
                    wct.reorder(mControlBarDisplay.getDisplayAreaInfo().token, /* onTop= */ false);
                }
            };

    private final BroadcastReceiver mSystemBarButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            moveToState(CONTROL_BAR);
        }
    };

    /**
     * Initializes the controller
     */
    @Inject
    public CarDisplayAreaController(Context applicationContext,
            FullscreenTaskListener fullscreenTaskListener,
            ShellExecutor shellExecutor,
            CarServiceProvider carServiceProvider,
            CarDisplayAreaOrganizer organizer,
            QSHost host,
            CarUiPortraitDisplaySystemBarsController carUiPortraitDisplaySystemBarsController,
            CommandQueue commandQueue,
            CarDeviceProvisionedController deviceProvisionedController,
            LoadingViewController loadingViewController,
            SyncTransactionQueue syncQueue,
            ConfigurationController configurationController,
            CarDisplayAreaTransitions carDisplayAreaTransitions,
            TaskCategoryManager taskCategoryManager
    ) {
        mApplicationContext = applicationContext;
        mOrganizer = organizer;
        mShellExecutor = shellExecutor;
        mCarServiceProvider = carServiceProvider;
        mFullscreenTaskMonitorListener = fullscreenTaskListener;
        mConfigurationController = configurationController;
        mCarDisplayAreaTransitions = carDisplayAreaTransitions;

        mUiModeManager = host.getUserContext().getSystemService(UiModeManager.class);

        Resources resources = applicationContext.getResources();
        mNotificationCenterComponent = ComponentName.unflattenFromString(resources.getString(
                R.string.config_notificationCenterActivity));
        mCarUiDisplaySystemBarsController = carUiPortraitDisplaySystemBarsController;
        mCarDeviceProvisionedController = deviceProvisionedController;
        mLoadingViewController = loadingViewController;
        mTitleBarViewHeight = resources.getDimensionPixelSize(R.dimen.title_bar_height);

        mSyncQueue = syncQueue;
        mCarDisplayAreaTouchHandler = new CarDisplayAreaTouchHandler(
                new HandlerExecutor(applicationContext.getMainThreadHandler()));

        BroadcastReceiver launcherRequestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(INTENT_EXTRA_LAUNCHER_READY)) {
                    mLoadingViewController.stop();
                } else if (intent.hasExtra(INTENT_EXTRA_CONTROL_BAR_HEIGHT_CHANGE)) {
                    int newControlBarHeight = intent.getIntExtra(
                            INTENT_EXTRA_CONTROL_BAR_HEIGHT_CHANGE, mControlBarDisplayHeight);
                    if (newControlBarHeight == mControlBarDisplayHeight) {
                        return;
                    }
                    logIfDebuggable("Receive new controlbar height =" + newControlBarHeight);
                    mControlBarDisplayHeight = newControlBarHeight;
                } else if (intent.hasExtra(INTENT_EXTRA_COLLAPSE_APPLICATION_PANEL)) {
                    moveToState(CONTROL_BAR);
                }
            }
        };
        mApplicationContext.registerReceiverForAllUsers(launcherRequestReceiver,
                new IntentFilter(REQUEST_FROM_LAUNCHER), null, null, Context.RECEIVER_EXPORTED);

        LocalBroadcastManager.getInstance(mApplicationContext).registerReceiver(
                mSystemBarButtonReceiver,
                new IntentFilter(COLLAPSE_APPLICATION_PANEL));

        mCarUiDisplaySystemBarsController.registerCallback(mApplicationContext.getDisplayId(),
                mSystemBarControllerCallback);
        commandQueue.addCallback(this);

        mControlBarDisplayHeight = resources.getDimensionPixelSize(
                R.dimen.control_bar_height);
        mFullDisplayHeight = resources.getDimensionPixelSize(
                R.dimen.full_app_display_area_height);
        mDefaultDisplayHeight = resources.getDimensionPixelSize(
                R.dimen.default_app_display_area_height);

        mDpiDensity = mOrganizer.getDpiDensity();
        mTotalScreenHeight = resources.getDimensionPixelSize(
                R.dimen.total_screen_height);
        mTotalScreenWidth = resources.getDimensionPixelSize(
                R.dimen.total_screen_width);

        mTaskCategoryManager = taskCategoryManager;
        mEnterExitAnimationDurationMs = resources.getInteger(
                R.integer.enter_exit_animation_foreground_display_area_duration_ms);

        // Get bottom nav bar height.
        mNavBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        if (mNavBarHeight > 0) {
            mNavBarBounds.set(0, mTotalScreenHeight - mNavBarHeight, mTotalScreenWidth,
                    mTotalScreenHeight);
        }

        mScreenHeightWithoutNavBar = mTotalScreenHeight - mNavBarBounds.height();
        mTitleBarHeight = mStatusBarHeight;
        mAssistUtils = new AssistUtils(applicationContext);

        mTitleBarDragThreshold = resources.getDimensionPixelSize(
                R.dimen.title_bar_display_area_touch_drag_threshold);

        mForegroundDAComponentsVisibilityMap = new HashMap<>();
        for (String component : mApplicationContext.getResources().getStringArray(
                R.array.config_foregroundDAComponents)) {
            mForegroundDAComponentsVisibilityMap.put(component, false);
        }
    }

    private static void logIfDebuggable(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    /**
     * Returns options that specify the {@link RootDisplayArea} to attach the confirmation window.
     * {@code null} if the {@code rootDisplayAreaId} is {@link FEATURE_UNDEFINED}.
     */
    @Nullable
    private static Bundle getOptionWithRootDisplayArea(int rootDisplayAreaId) {
        // In case we don't care which root display area the window manager is specifying.
        if (rootDisplayAreaId == FEATURE_UNDEFINED) {
            return null;
        }

        Bundle options = new Bundle();
        options.putInt(KEY_ROOT_DISPLAY_AREA_ID, rootDisplayAreaId);
        return options;
    }

    private void setImmersive(boolean immersive) {
        logIfDebuggable("setImmersive " + immersive);
        if (mIsForegroundDaFullScreen == immersive) {
            return;
        }
        mIsForegroundDaFullScreen = immersive;
        if (mIsForegroundDaFullScreen) {
            if (!isForegroundDaVisible()) {
                makeForegroundDaVisible(true);
            }
            moveToState(FULL);
        } else {
            moveToState(FULL_TO_DEFAULT);
        }
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        if (mIsNotificationCenterOnTop && mCurrentForegroundDaState == DEFAULT) {
            moveToState(CONTROL_BAR);
        }
    }

    @Override
    public void animateExpandNotificationsPanel() {
        String name = mNotificationCenterComponent.flattenToShortString();
        if (isHostingDefaultApplicationDisplayAreaVisible()
                && mForegroundDAComponentsVisibilityMap.getOrDefault(name, false)) {
            // notifications activity already visible
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(mNotificationCenterComponent);
        mApplicationContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    /** Registers the DA organizer. */
    public void init() {
        registerProvisionedStateListener();
        registerDisplayArea();
    }

    private void registerProvisionedStateListener() {
        mCarDeviceProvisionedController.addCallback(mCarDeviceProvisionedListener);
    }

    /** Registers the DA organizer. */
    public void registerDisplayArea() {
        logIfDebuggable("Setup display area");

        // Register DA organizer.
        registerOrganizer();

        // Pre-calculate the foreground and background display bounds for different configs.
        setDefaultBounds();

        // show the title bar window
        showTitleBar();

        mConfigurationController.addCallback(this);

        mCarDisplayAreaTouchHandler.registerTouchEventListener(mOnDragDisplayAreaListener);
        mCarDisplayAreaTouchHandler.enable(true);

        mCarServiceProvider.addListener(car -> {
            mCar = car;
            logIfDebuggable("car service connected");
            registerUserRestrictionsListener();
        });

        ActivityTaskManager.getInstance().registerTaskStackListener(
                mOnActivityRestartAttemptListener);

        if (mFullscreenTaskMonitorListener instanceof CarFullscreenTaskMonitorListener) {
            ((CarFullscreenTaskMonitorListener) mFullscreenTaskMonitorListener).addTaskListener(
                    mOnTaskChangeListener);
        }
    }

    private void registerUserRestrictionsListener() {
        if (mCar == null) {
            Log.e(TAG, "Failed to initialize car");
            return;
        }

        mCarUxRestrictionsManager = mCar.getCarManager(CarUxRestrictionsManager.class);
        if (mCarUxRestrictionsManager == null) {
            Log.e(TAG, "UX Restriction service is not available");
            return;
        }

        mCarUxRestrictionsManager.registerListener(mUxRestrictionsListener);
        handleUxRestrictionsChange(mCarUxRestrictionsManager.getCurrentCarUxRestrictions());
    }

    private void unregisterUserRestrictionsListener() {
        if (mCarUxRestrictionsManager == null) {
            Log.e(TAG, "UX Restriction service is not available");
            return;
        }
        mCarUxRestrictionsManager.unregisterListener();
    }

    void updateVoicePlateActivityMap() {
        logIfDebuggable("updateVoicePlateActivityMap");
        CarActivityManager carAm = (CarActivityManager) mCar.getCarManager(
                Car.CAR_ACTIVITY_SERVICE);

        mTaskCategoryManager.updateFullScreenActivities();
        for (ComponentName componentName : mTaskCategoryManager.getFullScreenActivitiesList()) {
            logIfDebuggable("adding the following component to voice plate: " + componentName);
            CarDisplayAreaUtils.setPersistentActivity(carAm,
                    componentName,
                    FEATURE_VOICE_PLATE, "VoicePlate");
        }
    }

    private void addActiveTaskToForegroundDAMap(int taskId) {
        if (mActiveTasksOnForegroundDA == null) {
            mActiveTasksOnForegroundDA = new HashSet<>();
        }
        if (taskId != -1) {
            mActiveTasksOnForegroundDA.add(taskId);
            logIfDebuggable("added task to foreground DA: " + taskId + " total tasks: "
                    + mActiveTasksOnForegroundDA.size());
        }
    }

    private void addActiveTaskToBackgroundDAMap(int taskId) {
        if (mActiveTasksOnBackgroundDA == null) {
            mActiveTasksOnBackgroundDA = new HashSet<>();
        }
        if (taskId != -1) {
            mActiveTasksOnBackgroundDA.add(taskId);
            logIfDebuggable("added task to background DA: " + taskId + " total tasks: "
                    + mActiveTasksOnBackgroundDA.size());
        }
    }

    private void createTitleBar() {
        logIfDebuggable("createTitleBar");
        LayoutInflater inflater = LayoutInflater.from(mApplicationContext);
        mTitleBarView = inflater.inflate(R.layout.title_bar_display_area_view, null, true);
        mTitleBarView.setVisibility(View.VISIBLE);
        mGripBar = mTitleBarView.findViewById(R.id.grib_bar);
        mImmersiveToolBar = mTitleBarView.findViewById(R.id.immersive_tool_bar);

        // Show the confirmation.
        WindowManager.LayoutParams lp = getTitleBarWindowLayoutParams();
        getWindowManager().addView(mTitleBarView, lp);
    }

    private void launchBackgroundApp() {
        mApplicationContext.startActivityAsUser(mTaskCategoryManager.getDefaultMapsIntent(),
                UserHandle.CURRENT);
        logIfDebuggable("relaunching background app...");

    }

    private void launchControlBarApp() {
        logIfDebuggable("relaunching controlbar app..");
        mApplicationContext.startActivityAsUser(mTaskCategoryManager.getControlBarIntent(),
                UserHandle.CURRENT);
    }

    private void launchAppGrid(boolean skipNextPanelAction) {
        logIfDebuggable("launch AppGrid, skip next panel action =" + skipNextPanelAction);
        Intent appgridIntent = new Intent();
        appgridIntent.setComponent(mTaskCategoryManager.getAppGridComponent());
        mApplicationContext.startActivityAsUser(appgridIntent,
                UserHandle.CURRENT);
        if (skipNextPanelAction) {
            mSkipNextPanelAction = true;
        }
    }

    private WindowManager.LayoutParams getTitleBarWindowLayoutParams() {
        logIfDebuggable("getTitleBarWindowLayoutParams");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mTitleBarHeight,
                TITLE_BAR_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.statusBars());
        // Trusted overlay so touches outside the touchable area are allowed to pass through
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.setTitle("TitleBar");
        lp.gravity = Gravity.BOTTOM;
        lp.token = mWindowToken;
        return lp;
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        logIfDebuggable("onConfigChanged old config =" + mConfiguration);
        logIfDebuggable("onConfigChanged new config =" + newConfig);
        mConfiguration.updateFrom(newConfig);
        int currentNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (mIsUserSetupInProgress) {
            logIfDebuggable("Skip on config change for suw");
            return;
        }
        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES && !mIsUiModeNight) {
            logIfDebuggable("change to night mode");
            removeTitleBar();
            mUiModeManager.setNightModeActivated(true);
            createTitleBar();
            mIsUiModeNight = true;
        } else if (currentNightMode == Configuration.UI_MODE_NIGHT_NO && mIsUiModeNight) {
            logIfDebuggable("change to day mode");
            removeTitleBar();
            mUiModeManager.setNightModeActivated(false);
            createTitleBar();
            mIsUiModeNight = false;
        }
        //TODO(b/373464094): Should not need to reset bounds here.
        setDefaultBounds();
        moveToState(mCurrentForegroundDaState, /* animate= */ false);
    }

    /**
     * Hide the title bar view
     */
    public void hideTitleBar() {
        logIfDebuggable("hideTitleBar");
        if (mTitleBarView != null) {
            mTitleBarView.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Remove the title bar view
     */
    public void removeTitleBar() {
        logIfDebuggable("removeTitleBar");
        if (mTitleBarView != null) {
            mTitleBarWindowManager.removeView(mTitleBarView);
        }
    }

    private void showTitleBar() {
        logIfDebuggable("showTitleBar");

        if (mTitleBarView != null) {
            logIfDebuggable("title bar exist");
            mTitleBarView.setVisibility(View.VISIBLE);
            return;
        }
        logIfDebuggable("recreaste title bar since it's not exist");
        hideTitleBar();
        createTitleBar();
    }

    private WindowManager getWindowManager() {
        Bundle options = getOptionWithRootDisplayArea(FOREGROUND_DISPLAY_AREA_ROOT);
        if (mTitleBarWindowManager == null || mTitleBarWindowContext == null) {
            // Create window context to specify the RootDisplayArea
            mTitleBarWindowContext = mApplicationContext.createWindowContext(
                    TITLE_BAR_WINDOW_TYPE, options);
            mTitleBarWindowManager = mTitleBarWindowContext.getSystemService(WindowManager.class);
            return mTitleBarWindowManager;
        }

        // Update the window context and window manager to specify the RootDisplayArea
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        try {
            wms.attachWindowContextToDisplayArea(mAppThread,
                    mTitleBarWindowContext.getWindowContextToken(),
                    TITLE_BAR_WINDOW_TYPE, mApplicationContext.getDisplayId(), options);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        return mTitleBarWindowManager;
    }

    /** Pre-calculates the display bounds for different DA's. */
    void setDefaultBounds() {
        logIfDebuggable("setting default bounds for all the DA's");
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        int controlBarTop = mScreenHeightWithoutNavBar - mControlBarDisplayHeight;
        int applicationTop = controlBarTop - mDefaultDisplayHeight;
        int foregroundTop = applicationTop - mTitleBarHeight;
        int foregroundHeight = controlBarTop - foregroundTop;

        // Bottom nav bar. Bottom nav bar height will be 0 if the nav bar is present on the sides.
        Rect backgroundBounds = new Rect(0, 0, mTotalScreenWidth, controlBarTop);
        Rect controlBarBounds = new Rect(0, controlBarTop, mTotalScreenWidth,
                mScreenHeightWithoutNavBar);
        Rect foregroundBounds = new Rect(0, foregroundTop, mTotalScreenWidth, controlBarTop);
        Rect foregroundBoundsInvisible = new Rect(0, mTotalScreenHeight, mTotalScreenWidth,
                mTotalScreenHeight + foregroundHeight);
        Rect voicePlateBounds = new Rect(0, 0, mTotalScreenWidth,
                mScreenHeightWithoutNavBar);
        Rect titleBarBounds = new Rect(0,
                foregroundTop, mTotalScreenWidth, applicationTop);
        Rect titleBarViewBounds = new Rect(0,
                foregroundTop - mTitleBarViewHeight, mTotalScreenWidth, foregroundTop);
        Rect imeBounds = new Rect(0, 0, mTotalScreenWidth, mScreenHeightWithoutNavBar);
        Rect visibleBackgroundBounds = new Rect(0, mStatusBarHeight, mTotalScreenWidth,
                mScreenHeightWithoutNavBar - mDefaultDisplayHeight - mControlBarDisplayHeight
                        - mTitleBarHeight);
        Rect defaultAppBounds = new Rect(0, applicationTop, mTotalScreenWidth, controlBarTop);

        // Adjust the bounds based on the nav bar.
        // Populate the bounds depending on where the nav bar is.
        if (mNavBarBounds.left == 0 && mNavBarBounds.top == 0) {
            // Left nav bar.
            backgroundBounds.left = mNavBarBounds.right;
            controlBarBounds.left = mNavBarBounds.right;
            foregroundBounds.left = mNavBarBounds.right;
            titleBarBounds.left = mNavBarBounds.right;
        } else if (mNavBarBounds.top == 0) {
            // Right nav bar.
            backgroundBounds.right = mNavBarBounds.left;
            controlBarBounds.right = mNavBarBounds.left;
            foregroundBounds.right = mNavBarBounds.left;
            titleBarBounds.right = mNavBarBounds.left;
        }

        mBackgroundApplicationDisplayBounds.set(backgroundBounds);
        mControlBarDisplayBounds.set(controlBarBounds);
        mForegroundApplicationDisplayBounds.set(foregroundBounds);
        mForegroundApplicationDisplayBoundsInvisible.set(foregroundBoundsInvisible);
        mTitleBarDisplayBounds.set(titleBarBounds);
        mVoicePlateDisplayBounds.set(voicePlateBounds);
        mTitleBarViewBounds.set(titleBarBounds);
        mCarDisplayAreaTouchHandler.setTitleBarViewBounds(titleBarViewBounds);
        mImeBounds.set(imeBounds);
        mVisibleBackgroundBounds.set(visibleBackgroundBounds);
        mCarDisplayAreaTouchHandler.setTrackingBounds(visibleBackgroundBounds);
        mDefaultAppDisplayBounds.set(defaultAppBounds);
        mOrganizer.setControlBarHeight(mControlBarDisplayHeight);

        logIfDebuggable("setDefaultBounds mBackgroundApplicationDisplayBounds= "
                + mBackgroundApplicationDisplayBounds
                + ", mForegroundApplicationDisplayBounds= " + mForegroundApplicationDisplayBounds
                + ", mForegroundApplicationDisplayBoundsInvisible= "
                + mForegroundApplicationDisplayBoundsInvisible
                + ", mControlBarDisplayBounds= " + mControlBarDisplayBounds
                + ", mTitleBarDisplayBounds= " + mTitleBarDisplayBounds
                + ", mVoicePlateDisplayBounds= " + mVoicePlateDisplayBounds
                + ", titleBarViewBounds= " + titleBarViewBounds);

        // Set the initial bounds for first and second displays.
        updateBounds();
        mIsForegroundDaFullScreen = false;
    }

    /** Updates the default and background display bounds for the given config. */
    private void updateBounds() {
        logIfDebuggable("updateBounds");
        if (mIsUserSetupInProgress) {
            logIfDebuggable("skip setup bounds for suw");
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        Rect foregroundApplicationDisplayBound = mForegroundApplicationDisplayBounds;
        Rect titleBarDisplayBounds = mTitleBarViewBounds;
        Rect voicePlateDisplayBounds = mVoicePlateDisplayBounds;
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds;
        Rect controlBarDisplayBound = mControlBarDisplayBounds;
        Rect imeBounds = mImeBounds;

        if (!mIsDisplayAreaReady) {
            return;
        }

        if (mForegroundApplicationsDisplay == null) {
            logIfDebuggable("mForegroundApplicationsDisplay is null");
            return;
        }
        if (mImeContainerDisplayArea == null) {
            logIfDebuggable("mImeContainerDisplayArea is null");
            return;
        }
        if (mTitleBarDisplay == null) {
            logIfDebuggable("mTitleBarDisplay is null");
            return;
        }
        if (mVoicePlateDisplay == null) {
            logIfDebuggable("mVoicePlateDisplay is null");
            return;
        }
        if (mBackgroundApplicationDisplay == null) {
            logIfDebuggable("mBackgroundApplicationDisplay is null");
            return;
        }
        if (mControlBarDisplay == null) {
            logIfDebuggable("mControlBarDisplay is null");
            return;
        }

        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationsDisplay.getDisplayAreaInfo().token;
        WindowContainerToken imeRootDisplayToken =
                mImeContainerDisplayArea.getDisplayAreaInfo().token;
        WindowContainerToken titleBarDisplayToken =
                mTitleBarDisplay.getDisplayAreaInfo().token;
        WindowContainerToken voicePlateDisplayToken =
                mVoicePlateDisplay.getDisplayAreaInfo().token;
        WindowContainerToken backgroundDisplayToken =
                mBackgroundApplicationDisplay.getDisplayAreaInfo().token;
        WindowContainerToken controlBarDisplayToken =
                mControlBarDisplay.getDisplayAreaInfo().token;

        // Default TDA
        int foregroundDisplayWidthDp =
                foregroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int foregroundDisplayHeightDp =
                foregroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(foregroundDisplayToken, foregroundApplicationDisplayBound);
        wct.setScreenSizeDp(foregroundDisplayToken, foregroundDisplayWidthDp,
                foregroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(foregroundDisplayToken, foregroundDisplayWidthDp);

        // Title bar
        int titleBarDisplayWidthDp =
                titleBarDisplayBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int titleBarDisplayHeightDp =
                titleBarDisplayBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(titleBarDisplayToken, titleBarDisplayBounds);
        wct.setScreenSizeDp(titleBarDisplayToken, titleBarDisplayWidthDp,
                titleBarDisplayHeightDp);
        wct.setSmallestScreenWidthDp(titleBarDisplayToken, titleBarDisplayWidthDp);

        // voice plate
        int voicePlateDisplayWidthDp =
                voicePlateDisplayBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int voicePlateDisplayHeightDp =
                voicePlateDisplayBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(voicePlateDisplayToken, voicePlateDisplayBounds);
        wct.setScreenSizeDp(voicePlateDisplayToken, voicePlateDisplayWidthDp,
                voicePlateDisplayHeightDp);
        wct.setSmallestScreenWidthDp(voicePlateDisplayToken, voicePlateDisplayWidthDp);

        // background TDA
        int backgroundDisplayWidthDp =
                backgroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int backgroundDisplayHeightDp =
                backgroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(backgroundDisplayToken, backgroundApplicationDisplayBound);
        wct.setScreenSizeDp(backgroundDisplayToken, backgroundDisplayWidthDp,
                backgroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(backgroundDisplayToken, backgroundDisplayWidthDp);

        // IME
        int imeDisplayWidthDp =
                imeBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int imeDisplayHeightDp =
                imeBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(imeRootDisplayToken, imeBounds);
        wct.setScreenSizeDp(imeRootDisplayToken, imeDisplayWidthDp, imeDisplayHeightDp);
        wct.setSmallestScreenWidthDp(imeRootDisplayToken, imeDisplayWidthDp);


        // control bar
        int controlBarDisplayWidthDp =
                controlBarDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int controlBarDisplayHeightDp =
                controlBarDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(controlBarDisplayToken, controlBarDisplayBound);
        wct.setScreenSizeDp(controlBarDisplayToken, controlBarDisplayWidthDp,
                controlBarDisplayHeightDp);
        wct.setSmallestScreenWidthDp(controlBarDisplayToken, controlBarDisplayWidthDp);

        mSyncQueue.queue(wct);

        mSyncQueue.runInSync(t -> {
            Rect foregroundApplicationAndTitleBarDisplayBound = new Rect(0, -mTitleBarHeight,
                    foregroundApplicationDisplayBound.width(),
                    foregroundApplicationDisplayBound.height());
            t.setWindowCrop(mForegroundApplicationsDisplay.getLeash(),
                    foregroundApplicationAndTitleBarDisplayBound);
            t.setPosition(mForegroundApplicationsDisplay.getLeash(),
                    foregroundApplicationDisplayBound.left,
                    foregroundApplicationDisplayBound.top);

            t.setWindowCrop(mVoicePlateDisplay.getLeash(),
                    voicePlateDisplayBounds.width(), voicePlateDisplayBounds.height());
            t.setPosition(mVoicePlateDisplay.getLeash(),
                    voicePlateDisplayBounds.left,
                    voicePlateDisplayBounds.top);

            t.setWindowCrop(mTitleBarDisplay.getLeash(),
                    titleBarDisplayBounds.width(), titleBarDisplayBounds.height());
            t.setPosition(mTitleBarDisplay.getLeash(),
                    titleBarDisplayBounds.left, -mTitleBarHeight);

            t.setWindowCrop(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.width(),
                    backgroundApplicationDisplayBound.height());
            t.setPosition(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.left,
                    backgroundApplicationDisplayBound.top);

            t.setWindowCrop(mControlBarDisplay.getLeash(),
                    controlBarDisplayBound.width(), controlBarDisplayBound.height());
            t.setPosition(mControlBarDisplay.getLeash(),
                    controlBarDisplayBound.left,
                    controlBarDisplayBound.top);
        });
    }

    private void setToDefaultLayers(@NonNull SurfaceControl.Transaction transaction) {
        logIfDebuggable("setToDefaultLayers");
        transaction.setLayer(mBackgroundApplicationDisplay.getLeash(),
                BACKGROUND_LAYER_INDEX);
        transaction.setLayer(mForegroundApplicationsDisplay.getLeash(),
                FOREGROUND_LAYER_INDEX);
        transaction.setLayer(mTitleBarDisplay.getLeash(), TITLE_BAR_LAYER_INDEX);
        transaction.setLayer(mVoicePlateDisplay.getLeash(),
                VOICE_PLATE_LAYER_SHOWN_INDEX);
        transaction.setLayer(mControlBarDisplay.getLeash(),
                CONTROL_BAR_LAYER_INDEX);

        transaction.setWindowCrop(mControlBarDisplay.getLeash(),
                mControlBarDisplayBounds.width(), mControlBarDisplayBounds.height());
        transaction.setPosition(mControlBarDisplay.getLeash(),
                mControlBarDisplayBounds.left,
                mControlBarDisplayBounds.top);
        transaction.setWindowCrop(mBackgroundApplicationDisplay.getLeash(),
                mBackgroundApplicationDisplayBounds.width(),
                mBackgroundApplicationDisplayBounds.height());
        transaction.setPosition(mBackgroundApplicationDisplay.getLeash(),
                mBackgroundApplicationDisplayBounds.left,
                mBackgroundApplicationDisplayBounds.top);
    }

    /** Registers DA organizer. */
    private void registerOrganizer() {
        List<DisplayAreaAppearedInfo> foregroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(FOREGROUND_DISPLAY_AREA_ROOT);
        if (foregroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch default applications");
        }

        List<DisplayAreaAppearedInfo> defaultAppDisplayAreaInfos =
                mOrganizer.registerOrganizer(FEATURE_DEFAULT_TASK_CONTAINER);
        for (DisplayAreaAppearedInfo info : defaultAppDisplayAreaInfos) {
            DisplayAreaInfo daInfo = info.getDisplayAreaInfo();
            if (daInfo.rootDisplayAreaId == FOREGROUND_DISPLAY_AREA_ROOT) {
                mDefaultAppDisplay = info;
            }
        }
        if (mDefaultAppDisplay == null) {
            throw new IllegalStateException("Can't find display to launch default applications");
        }

        List<DisplayAreaAppearedInfo> parent =
                mOrganizer.registerOrganizer(FEATURE_TASKDISPLAYAREA_PARENT);
        if (parent.size() != 1) {
            throw new IllegalStateException("Can't find parent display area");
        }

        List<DisplayAreaAppearedInfo> titleBarDisplayAreaInfo =
                mOrganizer.registerOrganizer(FEATURE_TITLE_BAR);
        if (titleBarDisplayAreaInfo.size() != 1) {
            throw new IllegalStateException("Can't find display to launch title bar");
        }

        List<DisplayAreaAppearedInfo> voicePlateDisplayAreaInfo =
                mOrganizer.registerOrganizer(FEATURE_VOICE_PLATE);
        if (voicePlateDisplayAreaInfo.size() != 1) {
            throw new IllegalStateException("Can't find display to launch voice plate");
        }

        List<DisplayAreaAppearedInfo> backgroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(BACKGROUND_TASK_CONTAINER);
        if (backgroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch activity in background");
        }

        List<DisplayAreaAppearedInfo> controlBarDisplayAreaInfos =
                mOrganizer.registerOrganizer(CONTROL_BAR_DISPLAY_AREA);
        if (controlBarDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch audio control");
        }

        // Get the IME display area attached to the root hierarchy.
        List<DisplayAreaAppearedInfo> imeDisplayAreaInfos =
                mOrganizer.registerOrganizer(FEATURE_IME_PLACEHOLDER);
        for (DisplayAreaAppearedInfo info : imeDisplayAreaInfos) {
            DisplayAreaInfo daInfo = info.getDisplayAreaInfo();
            // Need to check the display for the multi displays platform.
            if (daInfo.rootDisplayAreaId == DisplayAreaOrganizer.FEATURE_ROOT
                    && daInfo.displayId == Display.DEFAULT_DISPLAY) {
                mImeContainerDisplayArea = info;
            }
        }
        // As we have only 1 display defined for each display area feature get the 0th index.
        mForegroundApplicationsDisplay = foregroundDisplayAreaInfos.get(0);
        mOrganizer.setForegroundApplicationsDisplay(mForegroundApplicationsDisplay);
        mTitleBarDisplay = titleBarDisplayAreaInfo.get(0);
        mVoicePlateDisplay = voicePlateDisplayAreaInfo.get(0);
        mBackgroundApplicationDisplay = backgroundDisplayAreaInfos.get(0);
        mOrganizer.setBackgroundApplicationDisplay(mBackgroundApplicationDisplay);
        mControlBarDisplay = controlBarDisplayAreaInfos.get(0);

        mCarDisplayAreaTransitions.registerCallback(
                mCarDisplayAreaTransitionsCallback);

        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        setToDefaultLayers(tx);
        tx.hide(mVoicePlateDisplay.getLeash());
        tx.hide(mForegroundApplicationsDisplay.getLeash());
        tx.apply(true);
        mIsDisplayAreaReady = true;
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.resetWindowsOffset();
        mOrganizer.unregisterOrganizer();
        mForegroundApplicationsDisplay = null;
        mTitleBarDisplay = null;
        mBackgroundApplicationDisplay = null;
        mControlBarDisplay = null;
        mVoicePlateDisplay = null;
        mImeContainerDisplayArea = null;
        mCarDisplayAreaTouchHandler.enable(false);
        ActivityTaskManager.getInstance()
                .unregisterTaskStackListener(mOnActivityRestartAttemptListener);
        mCarDeviceProvisionedController.removeCallback(mCarDeviceProvisionedListener);
        mTitleBarView.setVisibility(View.GONE);
        unregisterUserRestrictionsListener();
    }

    private void updateUserSetupState() {
        mIsUserSetupInProgress = mCarDeviceProvisionedController
                .isCurrentUserSetupInProgress();
        logIfDebuggable("mUserSetupInProgress changed to " + mIsUserSetupInProgress);

        mCarUiDisplaySystemBarsController.requestImmersiveModeForSUW(
                mApplicationContext.getDisplayId(), mIsUserSetupInProgress);

        if (!mIsDisplayAreaReady) {
            logIfDebuggable("DisplayAreas are not ready");
            return;
        }

        mIsForegroundAppRequestingImmersiveMode = mIsUserSetupInProgress;

        if (mIsUserSetupInProgress) {
            if (!isForegroundDaVisible()) {
                hideTitleBar();
                makeForegroundDaVisible(true);
            }
            setControlBarVisibility(false);
            updateDABoundsForSUW(true);
        } else {
            makeForegroundDaVisible(false);
            showTitleBar();
            setControlBarVisibility(true);
            launchBackgroundApp();
            launchControlBarApp();
            launchAppGrid(true);
            updateDABoundsForSUW(false);
        }
    }

    /**
     * Start background and control bar app.
     */
    public void onBootComplete() {
        mOnBootCompleteCalled = true;
        if (!mIsUserSetupInProgress) {
            moveToState(CONTROL_BAR);
            launchBackgroundApp();
            launchControlBarApp();
            launchAppGrid(true);
            setDefaultBounds();
        }
    }

    /** Bypass the typical fullscreen flow specifically for SUW */
    void updateDABoundsForSUW(boolean immersive) {
        if (immersive) {
            makeForegroundDAFullScreen(/* setFullPosition= */ true, /* showTitleBar= */ false);
        } else {
            setDefaultBounds();
            moveToState(CONTROL_BAR);
        }
    }

    private void updateApplicationPanel(ActivityManager.RunningTaskInfo taskInfo) {
        logIfDebuggable("updateApplicationPanel " + taskInfo);

        mCurrentForegroundTask = taskInfo;
        updateSystemButtonState(taskInfo);
        broadcastForegroundDAVisibilityChange(/* visible= */ true);

        ComponentName componentName = taskInfo.baseIntent.getComponent();
        String name = componentName.flattenToShortString();

        // check if the foreground DA is visible to the user. If not, make it visible.
        if (mCurrentForegroundDaState == CONTROL_BAR) {
            logIfDebuggable("opening DA on request for cmp: " + componentName);
            moveToState(DEFAULT);
        }
        // just let the task launch and don't change the state of the foreground DA.
        addActiveTaskToForegroundDAMap(taskInfo.taskId);
        mForegroundDAComponentsVisibilityMap.replaceAll((n, v) -> name.equals(n));
    }

    private void updateSystemButtonState(ActivityManager.RunningTaskInfo taskInfo) {
        mIsNotificationCenterOnTop = mTaskCategoryManager.isNotificationActivity(taskInfo);
    }

    private boolean isHostingDefaultApplicationDisplayAreaVisible() {
        return mIsHostingDefaultApplicationDisplayAreaVisible;
    }

    boolean isDisplayAreaAnimating() {
        return mOrganizer != null && mOrganizer.isDisplayAreaAnimating();
    }

    void showVoicePlateDisplayArea() {
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        // Reset the layer for voice plate. This is needed as when the tasks are launched on
        // other DA's those are brought to the top.
        tx.setLayer(mBackgroundApplicationDisplay.getLeash(), BACKGROUND_LAYER_INDEX);
        tx.setLayer(mForegroundApplicationsDisplay.getLeash(), FOREGROUND_LAYER_INDEX);
        tx.setLayer(mVoicePlateDisplay.getLeash(), VOICE_PLATE_LAYER_SHOWN_INDEX);
        tx.setLayer(mControlBarDisplay.getLeash(), CONTROL_BAR_LAYER_INDEX);
        tx.show(mVoicePlateDisplay.getLeash());
        tx.apply();
    }

    private boolean shouldTaskShowOnForegroundDA(@Nullable TaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.baseIntent.getComponent() == null) {
            logIfDebuggable(
                    "Should not show on application panel since task in invalid, " + taskInfo);
            return false;
        }

        ComponentName componentName = taskInfo.baseIntent.getComponent();

        // Voice plate will be shown as the top most layer. Also, we don't want to change the
        // state of the DA's when voice plate is shown.
        boolean isVoicePlate = mTaskCategoryManager.isFullScreenActivity(componentName);
        if (isVoicePlate) {
            logIfDebuggable("Should not show on application panel since task is for voice plate");
            return false;
        }

        boolean isControlBar = mTaskCategoryManager.isControlBar(componentName);
        boolean isBackgroundApp = mTaskCategoryManager.isBackgroundApp(componentName);

        if (isBackgroundApp) {
            // we don't want to change the state of the foreground DA when background
            // apps are launched.
            logIfDebuggable("Should not show on application panel since task is for background");
            return false;
        }

        if (isControlBar) {
            // we don't want to change the state of the foreground DA when
            // controlbar apps are launched.
            mControlBarTaskId = taskInfo.taskId;
            logIfDebuggable("Should not show on application panel since task is the control bar");
            return false;
        }

        // Check is there is an existing session running for assist, hide it.
        if (mAssistUtils.isSessionRunning()) {
            resetVoicePlateDisplayArea();
        }

        if (mTaskCategoryManager.shouldIgnoreForApplicationPanel(taskInfo)) {
            return false;
        }

        // Any task that does NOT meet all the below criteria should be ignored.
        // 1. displayAreaFeatureId should be FEATURE_DEFAULT_TASK_CONTAINER
        // 2. should be visible
        // 3. for the current user ONLY. System user launches some tasks on cluster that should
        //    not affect the state of the foreground DA
        // 4. any task that is manually defined to be ignored
        // 5. home activity. We use this activity as the wallpaper.
        return taskInfo.displayAreaFeatureId == FEATURE_DEFAULT_TASK_CONTAINER
                && taskInfo.userId == ActivityManager.getCurrentUser();
    }

    private void showBackgroundDisplayArea() {
        logIfDebuggable("showBackgroundDisplayArea");
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.setLayer(mBackgroundApplicationDisplay.getLeash(), BACKGROUND_LAYER_INDEX);
        tx.show(mBackgroundApplicationDisplay.getLeash());
        tx.apply(true);
    }


    void resetVoicePlateDisplayArea() {
        logIfDebuggable("reset voice plate");
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        tx.hide(mVoicePlateDisplay.getLeash());
        tx.apply(true);
    }

    private void moveToState(@PanelState int toState) {
        moveToState(toState, /* animate= */ true);
    }

    /**
     * Start animation to state
     */
    private void moveToState(@PanelState int  toState, boolean animate) {
        if (mIsUserSetupInProgress) {
            logIfDebuggable("suw in progress");
            // No animations while in setup
            return;
        }

        // TODO(b/379961161): make animation cancellable.
        if (isDisplayAreaAnimating()) {
            logIfDebuggable("Skip since panel is animating");
            return;
        }

        // TODO: currently the animations are only bottom/up. Make it more generic animations here.
        int fromPos = 0;
        int toPos = 0;
        mCurrentForegroundDaState = toState;
        int animationDurationMs = animate ? mEnterExitAnimationDurationMs : 0;
        logIfDebuggable("startAnimation to " + toState);
        switch (toState) {
            case CONTROL_BAR:
                // Foreground DA closes.
                fromPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                        - mControlBarDisplayHeight;
                toPos = mScreenHeightWithoutNavBar + mTitleBarHeight;
                animateToControlBarState(fromPos, toPos, animationDurationMs);
                mCarDisplayAreaTouchHandler.updateTitleBarVisibility(false);

                // Show the voice plate if there is an existing session running for assist.
                if (mAssistUtils.isSessionRunning()) {
                    showVoicePlateDisplayArea();
                }

                break;
            case FULL:
                fromPos =
                        isForegroundDaVisible() ? mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                                - mControlBarDisplayHeight
                                : mScreenHeightWithoutNavBar + mTitleBarHeight;
                toPos = mStatusBarHeight;
                animateToFullState(fromPos, toPos, animationDurationMs);
                break;
            case FULL_TO_DEFAULT:
                toPos = mVisibleBackgroundBounds.bottom;
                animateFullToDefaultState(fromPos, toPos, animationDurationMs);
                break;
            default:
                // Foreground DA opens to default height.
                // update the bounds to expand the foreground display area before starting
                // animations.
                fromPos = mScreenHeightWithoutNavBar + mTitleBarHeight;
                toPos = mVisibleBackgroundBounds.bottom;
                animateToDefaultState(fromPos, toPos, animationDurationMs);
        }
    }

    private void animateToControlBarState(int fromPos, int toPos, int durationMs) {
        logIfDebuggable("animateToControlBarState");
        animate(fromPos, toPos, CONTROL_BAR, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = false;
        broadcastForegroundDAVisibilityChange(false);
        mCurrentForegroundDaState = CONTROL_BAR;
    }

    private void animateToDraggingState(int fromPos, int toPos, int durationMs) {
        logIfDebuggable("animateToDragState");
        animate(fromPos, toPos, DRAGGING, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = false;
        mCurrentForegroundDaState = DRAGGING;
    }

    private void handleUxRestrictionsChange(@Nullable CarUxRestrictions carUxRestrictions) {
        if (carUxRestrictions == null) {
            logIfDebuggable("Current carUxRestrictions is null");
            return;
        }
        if (carUxRestrictions.isRequiresDistractionOptimization()
                && mCurrentForegroundDaState == FULL) {
            moveToState(FULL_TO_DEFAULT);
        }
    }

    private void animateToDefaultState(int fromPos, int toPos, int durationMs) {
        logIfDebuggable("animateToDefaultState");
        if (!isForegroundDaVisible()) {
            makeForegroundDaVisible(true);
            showTitleBar();
        }

        animate(fromPos, toPos, DEFAULT, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = true;
        broadcastForegroundDAVisibilityChange(true);
        if (mCarDisplayAreaTouchHandler != null) {
            mCarDisplayAreaTouchHandler.updateTitleBarVisibility(true);
        }
        mCurrentForegroundDaState = DEFAULT;
    }

    private void animateFullToDefaultState(int fromPos, int toPos, int durationMs) {
        logIfDebuggable("animateFullToDefaultState" + isForegroundDaVisible() + ", toPos=" + toPos);
        mIsForegroundDaFullScreen = false;
        animate(fromPos, toPos, FULL_TO_DEFAULT, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = true;
        updateTitleBar(DEFAULT);
        setControlBarVisibility(true);
        setDefaultBounds();
        if (mCarDisplayAreaTouchHandler != null) {
            mCarDisplayAreaTouchHandler.updateTitleBarVisibility(true);
        }
        mCurrentForegroundDaState = DEFAULT;
    }

    private void animateToFullState(int fromPos, int toPos, int durationMs) {
        logIfDebuggable("animateToFullState, " + isForegroundDaVisible() + ", toPos=" + toPos
                + ", mControlBarDisplayHeight =" + mControlBarDisplayHeight
                + ", mStatusBarHeight" + mStatusBarHeight
                + ", mTitleBarHeight" + mTitleBarHeight
                + ", mDefaultDisplayHeight" + mDefaultDisplayHeight
                + ", mTotalScreenHeight= " + mTotalScreenHeight);
        if (!isForegroundDaVisible()) {
            makeForegroundDaVisible(true);
        }
        setControlBarVisibility(false);
        mBackgroundApplicationDisplayBounds.bottom = mTotalScreenHeight;
        makeForegroundDAFullScreen(/* setFullPosition= */ false, /* showTitleBar= */ true);
        animate(fromPos, toPos, FULL, durationMs);
        updateTitleBar(FULL);
        mIsHostingDefaultApplicationDisplayAreaVisible = true;
        if (mCarDisplayAreaTouchHandler != null) {
            mCarDisplayAreaTouchHandler.updateTitleBarVisibility(false);
        }
        mCurrentForegroundDaState = FULL;
    }

    private void updateTitleBar(@PanelState int panelState) {
        new Handler(Looper.getMainLooper()).post(() -> {
            logIfDebuggable("updateTitleBar visibility");
            mGripBar.setVisibility(panelState == FULL ? View.GONE : View.VISIBLE);
            mImmersiveToolBar.setVisibility(panelState == FULL ? View.VISIBLE : View.GONE);
        });
    }

    private void broadcastForegroundDAVisibilityChange(boolean visible) {
        logIfDebuggable("Broadcast application panel state change"
                + mCurrentForegroundTask + ", visible" + visible);
        Intent intent = new Intent(DISPLAY_AREA_VISIBILITY_CHANGED);
        intent.putExtra(INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE, visible);
        LocalBroadcastManager.getInstance(mApplicationContext).sendBroadcast(
                intent);

        Intent controlbarIntent = new Intent(REQUEST_FROM_SYSTEM_UI);
        controlbarIntent.putExtra(INTENT_EXTRA_IS_APPLICATION_PANEL_OPEN, visible);
        controlbarIntent.putExtra(INTENT_EXTRA_TOP_TASK_IN_APPLICATION_PANEL,
                mCurrentForegroundTask);
        mApplicationContext.sendBroadcastAsUser(controlbarIntent,
                new UserHandle(ActivityManager.getCurrentUser()));
    }

    /**
     * Update the bounds of foreground DA to cover full screen.
     *
     * @param setFullPosition whether or not the surface's position should be set to the full
     *                        position. Setting this to true will set the position to the full
     *                        screen while setting to false will use the default display bounds.
     */
    void makeForegroundDAFullScreen(boolean setFullPosition, boolean showTitleBar) {
        logIfDebuggable("make foregroundDA fullscreen");
        if (mForegroundApplicationsDisplay == null) {
            logIfDebuggable("skip as  mForegroundApplicationsDisplay is null");
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        int topBound = showTitleBar ? mStatusBarHeight : 0;
        int bottomBound = setFullPosition ? mTotalScreenHeight : mScreenHeightWithoutNavBar;

        int appSize = setFullPosition ? mTotalScreenHeight
                : (mTotalScreenHeight - mStatusBarHeight - mNavBarHeight);
        logIfDebuggable("appSize =" + appSize + ", topBound=" + topBound);

        Rect foregroundApplicationDisplayBounds = new Rect(0, topBound, mTotalScreenWidth,
                bottomBound);
        mForegroundApplicationDisplayBoundsFull.set(foregroundApplicationDisplayBounds);

        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationsDisplay.getDisplayAreaInfo().token;
        int foregroundDisplayWidthDp =
                foregroundApplicationDisplayBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int foregroundDisplayHeightDp =
                foregroundApplicationDisplayBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(foregroundDisplayToken, foregroundApplicationDisplayBounds);
        wct.setScreenSizeDp(foregroundDisplayToken, foregroundDisplayWidthDp,
                foregroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(foregroundDisplayToken,
                Math.min(foregroundDisplayWidthDp, foregroundDisplayHeightDp));

        WindowContainerToken titleBarDisplayToken =
                mTitleBarDisplay.getDisplayAreaInfo().token;
        Rect fullscreenTitleBarDisplayBounds = new Rect(0, 0, mTotalScreenWidth,
                mStatusBarHeight);

        int titleBarDisplayWidthDp =
                fullscreenTitleBarDisplayBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int titleBarDisplayHeightDp =
                fullscreenTitleBarDisplayBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(titleBarDisplayToken, fullscreenTitleBarDisplayBounds);
        wct.setScreenSizeDp(titleBarDisplayToken, titleBarDisplayWidthDp,
                titleBarDisplayHeightDp);
        wct.setSmallestScreenWidthDp(titleBarDisplayToken, titleBarDisplayWidthDp);

        mSyncQueue.queue(wct);

        mSyncQueue.runInSync(t -> {
            Rect foregroundApplicationAndTitleBarDisplayBound = new Rect(
                    foregroundApplicationDisplayBounds.left,
                    -topBound,
                    foregroundApplicationDisplayBounds.width(),
                    foregroundApplicationDisplayBounds.height());
            t.setWindowCrop(mForegroundApplicationsDisplay.getLeash(),
                    foregroundApplicationAndTitleBarDisplayBound);
            if (setFullPosition) {
                t.setPosition(mForegroundApplicationsDisplay.getLeash(), 0, 0);
            }
        });

        mIsForegroundDaFullScreen = true;
        mCurrentForegroundDaState = FULL;
    }

    void setControlBarVisibility(boolean show) {
        if (mControlBarDisplay == null) {
            logIfDebuggable("skip as mControlBarDisplay is null");
            return;
        }
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        // Reset the layer for voice plate. This is needed as when the tasks are launched on
        // other DA's those are brought to the top.
        tx.setLayer(mControlBarDisplay.getLeash(), CONTROL_BAR_LAYER_INDEX);
        if (show) {
            tx.show(mControlBarDisplay.getLeash());
        } else {
            tx.hide(mControlBarDisplay.getLeash());
        }
        tx.apply(true);
    }

    boolean isForegroundDaVisible() {
        return mIsForegroundDaVisible;
    }

    private void animate(int fromPos, int toPos, @PanelState int toState,
            int durationMs) {
        logIfDebuggable("animate to toPos =" + toPos);
        if (mOrganizer != null) {
            logIfDebuggable("animate mBackgroundApplicationDisplayBounds= "
                    + mBackgroundApplicationDisplayBounds);

            Rect foregroundBounds = new Rect();
            switch (mCurrentForegroundDaState) {
                case FULL -> foregroundBounds.set(mForegroundApplicationDisplayBoundsFull);
                case DEFAULT -> foregroundBounds.set(mForegroundApplicationDisplayBounds);
                case FULL_TO_DEFAULT -> foregroundBounds.set(mForegroundApplicationDisplayBounds);
                case DRAGGING -> foregroundBounds.set(mForegroundApplicationDisplayBounds);
                case CONTROL_BAR -> foregroundBounds.set(
                        mForegroundApplicationDisplayBoundsInvisible);
            }

            logIfDebuggable("animate mForegroundApplicationDisplayBounds= "
                    + foregroundBounds);

            mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                    foregroundBounds, mTitleBarViewBounds,
                    mBackgroundApplicationDisplay,
                    mForegroundApplicationsDisplay, mControlBarDisplay, mVoicePlateDisplay,
                    toState, durationMs, mControlBarStateCallback);
        }
    }

    void makeForegroundDaVisible(boolean isVisible) {
        logIfDebuggable("make foregroundDA visible? " + isVisible);
        if (!mIsDisplayAreaReady) {
            logIfDebuggable("DisplayAreas are not ready");
            return;
        }

        if (mForegroundApplicationsDisplay == null) {
            logIfDebuggable("skip as  mForegroundApplicationsDisplay is null");
            return;
        }
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        if (isVisible) {
            tx.show(mForegroundApplicationsDisplay.getLeash());
            mIsForegroundDaVisible = true;
        } else {
            tx.hide(mForegroundApplicationsDisplay.getLeash());
            mIsForegroundDaVisible = false;
        }
        tx.apply(true);
    }
}
