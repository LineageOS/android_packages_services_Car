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

package com.android.car.portraitlauncher.homeactivities;

import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.INTENT_EXTRA_IMMERSIVE_MODE_REQUESTED_SOURCE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_APP_GRID_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_COLLAPSE_APPLICATION;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_FG_TASK_VIEW_READY;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_IMMERSIVE_MODE_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_IMMERSIVE_MODE_REQUESTED;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_NOTIFICATIONS_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_RECENTS_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_SUW_IN_PROGRESS;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_ACTIVITY_RESTART_ATTEMPT;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_CALM_MODE_STARTED;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_COLLAPSE_MSG;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_DRIVE_STATE_CHANGED;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_HOME_SCREEN_LAYOUT_CHANGED;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_IMMERSIVE_REQUEST;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_MEDIA_INTENT;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_PANEL_READY;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_SUW_STATE_CHANGED;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.ON_TASK_MOVED_TO_FRONT;
import static com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason.createReason;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.CarLauncher;
import com.android.car.carlauncher.CarLauncherUtils;
import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.media.MediaIntentRouter;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.portraitlauncher.R;
import com.android.car.portraitlauncher.calmmode.PortraitCalmModeActivity;
import com.android.car.portraitlauncher.common.CarUiPortraitServiceManager;
import com.android.car.portraitlauncher.common.UserEventReceiver;
import com.android.car.portraitlauncher.panel.TaskViewPanel;
import com.android.car.portraitlauncher.panel.TaskViewPanelStateChangeReason;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;

/**
 * This home screen has maps running in the background hosted in a TaskView. At the bottom, there
 * is a control bar view that will display information regarding media/dialer. Any other
 * application other than assistant voice activity will be launched in the rootTaskView that is
 * displayed in the lower half of the screen.
 *
 * —--------------------------------------------------------------------
 * |                              Status Bar                            |
 * —--------------------------------------------------------------------
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                            Navigation app                          |
 * |                        (BackgroundTask View)                       |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                      App Space (Root Task View Panel)              |
 * |                      (This layer is above maps)                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                          Control Bar                               |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                             Nav Bar                                |
 * —--------------------------------------------------------------------
 *
 * In total this Activity has 3 TaskViews.
 * Background Task view:
 * - It only contains maps app.
 * - Maps app is manually started in this taskview.
 *
 * Root Task View (Part of Root Task View Panel):
 * - It acts as the default container. Which means all the apps will run inside it by default.
 *
 * Note: Root Task View Panel always overlap over the Background TaskView.
 *
 * Fullscreen Task View
 * - Used for voice assist applications
 */
public final class CarUiPortraitHomeScreen extends FragmentActivity {
    public static final String TAG = CarUiPortraitHomeScreen.class.getSimpleName();

    private static final boolean DBG = Build.IS_DEBUGGABLE;
    /** Identifiers for panels. */
    private static final int APPLICATION = 1;
    private static final int BACKGROUND = 2;
    private static final int FULLSCREEN = 3;
    private static final long IMMERSIVE_MODE_REQUEST_TIMEOUT = 500;
    private static final String SAVED_BACKGROUND_APP_COMPONENT_NAME =
            "SAVED_BACKGROUND_APP_COMPONENT_NAME";
    private static final IActivityTaskManager sActivityTaskManager =
            ActivityTaskManager.getService();
    private static final int INVALID_TASK_ID = -1;
    private final UserEventReceiver mUserEventReceiver = new UserEventReceiver();
    private final Configuration mConfiguration = new Configuration();

    private int mStatusBarHeight;
    private FrameLayout mContainer;
    private LinearLayout mControlBarView;
    // All the TaskViews & corresponding helper instance variables.
    private TaskViewControllerWrapper mTaskViewControllerWrapper;
    private ViewGroup mBackgroundAppArea;
    private ViewGroup mFullScreenAppArea;
    private boolean mIsBackgroundTaskViewReady;
    private boolean mIsFullScreenTaskViewReady;
    private int mNavBarHeight;
    private boolean mIsSUWInProgress;
    private TaskCategoryManager mTaskCategoryManager;
    private ActivityManager.RunningTaskInfo mCurrentTaskInRootTaskView;
    private boolean mIsNotificationCenterOnTop;
    private boolean mIsRecentsOnTop;
    private boolean mIsAppGridOnTop;
    private boolean mIsCalmMode;
    private TaskInfoCache mTaskInfoCache;
    private TaskViewPanel mRootTaskViewPanel;
    private boolean mSkipAppGridOnRestartAttempt;
    private int mAppGridTaskId = INVALID_TASK_ID;
    private final IntentHandler mMediaIntentHandler = new IntentHandler() {
        @Override
        public void handleIntent(Intent intent) {
            logIfDebuggable("handleIntent mCurrentTaskInRootTaskView: " + mCurrentTaskInRootTaskView
                    + ", incoming intent =" + intent);
            if (TaskCategoryManager.isMediaApp(mCurrentTaskInRootTaskView)
                    && mRootTaskViewPanel.isOpen()) {
                mRootTaskViewPanel.closePanel(createReason(ON_MEDIA_INTENT, intent.getComponent()));
                return;
            }

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(getDisplay().getDisplayId());

            startActivity(intent, options.toBundle());
        }
    };
    /**
     * Only resize the size of rootTaskView when SUW is in progress. This is to resize the height of
     * rootTaskView after status bar hide on SUW start.
     */
    private final View.OnLayoutChangeListener mHomeScreenLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int newHeight = bottom - top;
                int oldHeight = oldBottom - oldTop;
                if (oldHeight == newHeight) {
                    return;
                }
                logIfDebuggable("container height change from " + oldHeight + " to " + newHeight);
                if (mIsSUWInProgress) {
                    mRootTaskViewPanel.openFullScreenPanel(/* animated = */ false,
                            /* showToolBar = */ false, /* bottomAdjustment= */ 0,
                            createReason(ON_HOME_SCREEN_LAYOUT_CHANGED));
                }
            };
    private final View.OnLayoutChangeListener mControlBarOnLayoutChangeListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (bottom == oldBottom && top == oldTop) {
                    return;
                }

                logIfDebuggable("Control Bar layout changed!");
                updateTaskViewInsets();
                updateBackgroundTaskViewInsets();
                updateObscuredTouchRegion();
            };

    private ComponentName mUnhandledImmersiveModeRequestComponent;
    private long mUnhandledImmersiveModeRequestTimestamp;
    private boolean mUnhandledImmersiveModeRequest;
    // This listener lets us know when actives are added and removed from any of the display regions
    // we care about, so we can trigger the opening and closing of the app containers as needed.
    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            logIfDebuggable("On task created, task = " + taskId);
            if (componentName != null) {
                logIfDebuggable(
                        "On task created, task = " + taskId + " componentName " + componentName);
            }
            if (mTaskCategoryManager.isAppGridActivity(componentName)) {
                mAppGridTaskId = taskId;
            }
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            logIfDebuggable("On task moved to front, task = " + taskInfo.taskId + ", cmp = "
                    + taskInfo.baseActivity + ", isVisible=" + taskInfo.isVisible);
            if (!mRootTaskViewPanel.isReady()) {
                logIfDebuggable("Root Task View is not ready yet.");
                if (!TaskCategoryManager.isHomeIntent(taskInfo)
                        && !mTaskCategoryManager.isBackgroundApp(taskInfo)) {

                    cacheTask(taskInfo);
                }
                return;
            }

            TaskViewPanelStateChangeReason reason = createReason(ON_TASK_MOVED_TO_FRONT,
                    taskInfo.taskId, getVisibleActivity(taskInfo));
            handleTaskStackChange(taskInfo, reason);
        }

        /**
         * Called when a task is removed.
         * @param taskId id of the task.
         * @throws RemoteException
         */
        @Override
        public void onTaskRemoved(int taskId) throws RemoteException {
            super.onTaskRemoved(taskId);
            logIfDebuggable("onTaskRemoved taskId=" + taskId);
            if (mAppGridTaskId == taskId) {
                Log.e(TAG, "onTaskRemoved, App Grid task is removed.");
                mAppGridTaskId = INVALID_TASK_ID;
            }
        }

        /**
         * Called whenever IActivityManager.startActivity is called on an activity that is already
         * running, but the task is either brought to the front or a new Intent is delivered to it.
         *
         * @param taskInfo information about the task the activity was relaunched into
         * @param homeTaskVisible whether or not the home task is visible
         * @param clearedTask whether or not the launch activity also cleared the task as a part of
         * starting
         * @param wasVisible whether the activity was visible before the restart attempt
         */
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo taskInfo,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible)
                throws RemoteException {
            super.onActivityRestartAttempt(taskInfo, homeTaskVisible, clearedTask, wasVisible);

            logIfDebuggable("On Activity restart attempt, task = " + taskInfo.taskId + ", cmp ="
                    + taskInfo.baseActivity + " wasVisible = " + wasVisible);

            TaskViewPanelStateChangeReason reason = createReason(ON_ACTIVITY_RESTART_ATTEMPT,
                    taskInfo.taskId, getVisibleActivity(taskInfo));
            handleTaskStackChange(taskInfo, reason);
        }
    };

    private void handleCalmMode(ActivityManager.RunningTaskInfo taskInfo,
            @NonNull TaskViewPanelStateChangeReason reason) {
        if (!ON_TASK_MOVED_TO_FRONT.equals(reason.getReason())) {
            logIfDebuggable(
                    "Skip handling calm mode since the reason is not " + ON_TASK_MOVED_TO_FRONT);
            return;
        }
        if (mTaskCategoryManager.isFullScreenActivity(taskInfo)) {
            logIfDebuggable(
                    "Skip handling calm mode if new activity is full screen activity");
            return;
        }

        boolean wasCalmMode = mIsCalmMode;
        mIsCalmMode = mTaskCategoryManager.isCalmModeActivity(taskInfo);

        if (wasCalmMode && !mIsCalmMode) {
            exitCalmMode();
        } else if (!wasCalmMode && mIsCalmMode) {
            enterCalmMode(taskInfo);
        }
    }

    private void exitCalmMode() {
        logIfDebuggable("Exiting calm mode");
        PortraitCalmModeActivity.dismissCalmMode(getApplicationContext());
        setControlBarVisibility(/* isVisible = */ true, /* animate = */ true);
        notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE, WindowInsets.Type.systemBars());
    }

    private void enterCalmMode(ActivityManager.RunningTaskInfo taskInfo) {
        logIfDebuggable("Entering calm mode");
        if (mRootTaskViewPanel.isVisible()) {
            mRootTaskViewPanel.closePanel(
                    createReason(ON_CALM_MODE_STARTED, taskInfo.taskId,
                            getVisibleActivity(taskInfo)));
        }
        setControlBarVisibility(/* isVisible = */ false, /* animate = */ true);
        int windowInsetsType = WindowInsets.Type.navigationBars();
        notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE, windowInsetsType);
    }

    private void handleSystemBarButton(boolean isPanelVisible) {
        notifySystemUI(MSG_APP_GRID_VISIBILITY_CHANGE, boolToInt(
                isPanelVisible && mTaskCategoryManager.isAppGridActivity(
                        mCurrentTaskInRootTaskView)));
        notifySystemUI(MSG_NOTIFICATIONS_VISIBILITY_CHANGE, boolToInt(
                isPanelVisible && mTaskCategoryManager.isNotificationActivity(
                        mCurrentTaskInRootTaskView)));
        notifySystemUI(MSG_RECENTS_VISIBILITY_CHANGE, boolToInt(
                isPanelVisible && mTaskCategoryManager.isRecentsActivity(
                        mCurrentTaskInRootTaskView)));
    }

    private void handleFullScreenPanel(ActivityManager.RunningTaskInfo taskInfo) {
        adjustFullscreenSpacing(mTaskCategoryManager.isFullScreenActivity(taskInfo));
    }

    private void handleTaskStackChange(ActivityManager.RunningTaskInfo taskInfo,
            TaskViewPanelStateChangeReason reason) {

        mIsNotificationCenterOnTop = mTaskCategoryManager.isNotificationActivity(taskInfo);
        mIsRecentsOnTop = mTaskCategoryManager.isRecentsActivity(taskInfo);
        mIsAppGridOnTop = mTaskCategoryManager.isAppGridActivity(taskInfo);

        if (mTaskCategoryManager.isBackgroundApp(taskInfo)) {
            mTaskCategoryManager.setCurrentBackgroundApp(taskInfo.baseActivity);
        }

        handleFullScreenPanel(taskInfo);
        handleCalmMode(taskInfo, reason);

        if (!shouldUpdateApplicationPanelState(taskInfo)) {
            return;
        }

        mCurrentTaskInRootTaskView = taskInfo;

        if (mIsAppGridOnTop && !shouldOpenPanelForAppGrid(reason)) {
            logIfDebuggable("Panel should not open for app grid, check previous log for details");
            return;
        }

        if (shouldOpenFullScreenPanel(taskInfo)) {
            mRootTaskViewPanel.openFullScreenPanel(/* animated= */ true, /* showToolBar= */ true,
                    mNavBarHeight, reason);
        } else {
            mRootTaskViewPanel.openPanel(reason);
        }
    }

    /**
     * Determine if the Application panel should open for the AppGrid.
     *
     * <p> AppGrid is used as the application panel's
     * 1. background when panel is open, preventing the user from seeing an empty panel.
     * 2. foreground when panel is closed, putting any ongoing activities within the panel to
     * onStop state.
     *
     * <p> If the reason of panel state change is ON_TASK_MOVED_TO_FRONT, always returns false.
     * <p> If the reason of panel state change is ON_ACTIVITY_RESTART_ATTEMPT, check
     * {@link mSkipAppGridOnRestartAttempt}.
     */
    private boolean shouldOpenPanelForAppGrid(TaskViewPanelStateChangeReason reason) {
        if (ON_TASK_MOVED_TO_FRONT.equals(reason.getReason())) {
            logIfDebuggable("Skip panel action for app grid in onTaskMovedToFront");
            return false;
        } else if (ON_ACTIVITY_RESTART_ATTEMPT.equals(reason.getReason())
                && mSkipAppGridOnRestartAttempt) {
            logIfDebuggable(
                    "Skip panel action for app grid in onActivityRestartAttempt after manually "
                            + "close the panel");
            mSkipAppGridOnRestartAttempt = false;
            return false;
        }

        return true;
    }

    private CarUiPortraitServiceManager mCarUiPortraitServiceManager;
    private CarUiPortraitDriveStateController mCarUiPortraitDriveStateController;
    private boolean mReceivedNewIntent;

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    /**
     * Send both action down and up to be qualified as a back press. Set time for key events, so
     * they are not staled.
     */
    public static void sendVirtualBackPress() {
        long downEventTime = SystemClock.uptimeMillis();
        long upEventTime = downEventTime + 1;

        final KeyEvent keydown = new KeyEvent(downEventTime, downEventTime, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, /* repeat= */ 0, /* metaState= */ 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, /* scancode= */ 0, KeyEvent.FLAG_FROM_SYSTEM);
        final KeyEvent keyup = new KeyEvent(upEventTime, upEventTime, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK, /* repeat= */ 0, /* metaState= */ 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, /* scancode= */ 0, KeyEvent.FLAG_FROM_SYSTEM);

        InputManagerGlobal inputManagerGlobal = InputManagerGlobal.getInstance();
        inputManagerGlobal.injectInputEvent(keydown, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        inputManagerGlobal.injectInputEvent(keyup, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private static int boolToInt(Boolean b) {
        return b ? 1 : 0;
    }

    private static boolean intToBool(int val) {
        return val == 1;
    }

    private void setFocusToBackgroundApp() {
        if (mCurrentTaskInRootTaskView == null) {
            return;
        }
        try {
            sActivityTaskManager.setFocusedTask(mCurrentTaskInRootTaskView.taskId);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to set focus on background app: ", e);
        }
    }

    private boolean shouldOpenFullScreenPanel(ActivityManager.RunningTaskInfo taskInfo) {
        return taskInfo.baseActivity != null
                && taskInfo.baseActivity.equals(mUnhandledImmersiveModeRequestComponent)
                && mUnhandledImmersiveModeRequest
                && System.currentTimeMillis() - mUnhandledImmersiveModeRequestTimestamp
                < IMMERSIVE_MODE_REQUEST_TIMEOUT;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getApplicationContext().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            Intent launcherIntent = new Intent(this, CarLauncher.class);
            launcherIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launcherIntent);
            finish();
            return;
        }

        setContentView(R.layout.car_ui_portrait_launcher);

        registerUserEventReceiver();
        mTaskCategoryManager = new TaskCategoryManager(getApplicationContext());
        if (savedInstanceState != null) {
            String savedBackgroundAppName = savedInstanceState.getString(
                    SAVED_BACKGROUND_APP_COMPONENT_NAME);
            if (savedBackgroundAppName != null) {
                mTaskCategoryManager.setCurrentBackgroundApp(
                        ComponentName.unflattenFromString(savedBackgroundAppName));
            }
        }

        mTaskInfoCache = new TaskInfoCache(getApplicationContext());

        // Make the window fullscreen as GENERIC_OVERLAYS are supplied to the background task view
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Activity is running fullscreen to allow background task to bleed behind status bar
        mNavBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        logIfDebuggable("Navbar height: " + mNavBarHeight);
        mContainer = findViewById(R.id.container);
        setHomeScreenBottomPadding(mNavBarHeight);
        mContainer.addOnLayoutChangeListener(mHomeScreenLayoutChangeListener);

        mRootTaskViewPanel = findViewById(R.id.application_panel);

        mStatusBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        mControlBarView = findViewById(R.id.control_bar_area);
        mControlBarView.addOnLayoutChangeListener(mControlBarOnLayoutChangeListener);

        // Setting as trusted overlay to let touches pass through.
        getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
        // To pass touches to the underneath task.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        mContainer.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    boolean widthChanged = (right - left) != (oldRight - oldLeft);
                    boolean heightChanged = (bottom - top) != (oldBottom - oldTop);

                    if (widthChanged || heightChanged) {
                        onContainerDimensionsChanged();
                    }
                });

        // If we happen to be resurfaced into a multi display mode we skip launching content
        // in the activity view as we will get recreated anyway.
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            return;
        }

        mCarUiPortraitServiceManager = new CarUiPortraitServiceManager(/* activity= */ this,
                new IncomingHandler());
        initializeCards();
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
        mCarUiPortraitDriveStateController = new CarUiPortraitDriveStateController(
                getApplicationContext());
        MediaIntentRouter.getInstance().registerMediaIntentHandler(mMediaIntentHandler);

        if (mTaskViewControllerWrapper == null) {
            mTaskViewControllerWrapper = new RemoteCarTaskViewControllerWrapperImpl(
                    /* activity= */ this, this::onTaskViewControllerReady);
        }
    }

    private void onTaskViewControllerReady() {
        setUpRootTaskView();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        mReceivedNewIntent = true;
        // No state change on application panel. If there is no task in application panel, user will
        // see a surface view.

        mTaskViewControllerWrapper.updateAllowListedActivities(BACKGROUND,
                mTaskCategoryManager.getBackgroundActivitiesList());
        mTaskViewControllerWrapper.updateAllowListedActivities(FULLSCREEN,
                mTaskCategoryManager.getFullScreenActivitiesList());
        mTaskViewControllerWrapper.showEmbeddedTasks(new int[]{BACKGROUND, FULLSCREEN});
    }

    private void registerUserEventReceiver() {
        UserEventReceiver.Callback callback = new UserEventReceiver.Callback() {
            @Override
            public void onUserSwitching() {
                logIfDebuggable("On user switching");
                if (!isFinishing()) {
                    finish();
                }
            }

            @Override
            public void onUserUnlock() {
                logIfDebuggable("On user unlock");
                initTaskViews();
            }
        };
        mUserEventReceiver.register(this, callback);
    }

    private void initializeCards() {
        Set<HomeCardModule> homeCardModules = new androidx.collection.ArraySet<>();
        for (String providerClassName : getResources().getStringArray(
                R.array.config_homeCardModuleClasses)) {
            try {
                HomeCardModule cardModule = Class.forName(providerClassName).asSubclass(
                        HomeCardModule.class).getDeclaredConstructor().newInstance();

                cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */ this));
                homeCardModules.add(cardModule);
            } catch (IllegalAccessException | InstantiationException | ClassNotFoundException
                     | InvocationTargetException | NoSuchMethodException e) {
                Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : homeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView().getFragment());
        }
        transaction.commitNow();
    }

    private void collapseAppPanel() {
        logIfDebuggable("On collapse app panel");
        mRootTaskViewPanel.closePanel(createReason(ON_COLLAPSE_MSG));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int diff = mConfiguration.updateFrom(newConfig);
        logIfDebuggable("onConfigurationChanged with diff =" + diff);
        if ((diff & CONFIG_UI_MODE) == 0) {
            return;
        }
        initializeCards();
        Drawable background = getResources().getDrawable(R.drawable.control_bar_background,
                getTheme());
        mControlBarView.setBackground(background);
        mRootTaskViewPanel.post(() -> mRootTaskViewPanel.refresh(getTheme()));
        updateBackgroundTaskViewInsets();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mTaskCategoryManager.getCurrentBackgroundApp() == null) {
            return;
        }
        outState.putString(SAVED_BACKGROUND_APP_COMPONENT_NAME,
                mTaskCategoryManager.getCurrentBackgroundApp().flattenToString());
    }

    @Override
    protected void onDestroy() {
        mTaskViewControllerWrapper.onDestroy();
        mRootTaskViewPanel.onDestroy();
        mTaskCategoryManager.onDestroy();
        mUserEventReceiver.unregister();
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        mCarUiPortraitServiceManager.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the showEmbeddedTasks will make the task visible which will lead to opening of the panel
        // and that should be skipped for application panel  when the  home intent is sent. Because
        // that leads to CTS failures.
        if (mReceivedNewIntent) {
            mReceivedNewIntent = false;
        } else {
            mTaskViewControllerWrapper.showEmbeddedTasks(new int[]{APPLICATION});
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // This usually happens during user switch, SUW might starts before
        // SemiControlledCarTaskView get released. So manually clean the
        // SemiControlledCarTaskView allowlist to avoid null pointers.
        mTaskViewControllerWrapper.updateAllowListedActivities(BACKGROUND, List.of());
        mTaskViewControllerWrapper.updateAllowListedActivities(FULLSCREEN, List.of());
    }

    @Override
    @SuppressLint("MissingSuperCall")
    public void onBackPressed() {
        // ignore back presses
    }

    private boolean shouldUpdateApplicationPanelState(TaskInfo taskInfo) {
        if (mIsSUWInProgress) {
            logIfDebuggable("Skip application panel state change during suw");
            return false;
        }
        if (taskInfo.baseIntent.getComponent() == null) {
            logIfDebuggable("Should not show on application panel since base intent is null");
            return false;
        }

        // Fullscreen activities will open in a separate task view which will show on the top most
        // z-layer, that should not change the state of the application panel.
        if (mTaskCategoryManager.isFullScreenActivity(taskInfo)) {
            logIfDebuggable(
                    "Should not show on application panel since task is full screen activity");
            return false;
        }

        // Background activities will open in a separate task view which will show on the bottom
        // most z-layer, that should not change the state of the application panel.
        if (mTaskCategoryManager.isBackgroundApp(taskInfo)) {
            logIfDebuggable(
                    "Should not show on application panel since task is background activity");
            return false;
        }

        // Should not trigger application panel state change for the task that is not on current
        // user.
        // TODO(b/336850810): update logic for ABA if necessary.
        if (taskInfo.userId != ActivityManager.getCurrentUser()) {
            logIfDebuggable(
                    "Should not show on application panel since task is not on current user");
            return false;
        }

        // Should not trigger application panel state change for the task that is not allowed.
        if (mTaskCategoryManager.shouldIgnoreForApplicationPanel(taskInfo)) {
            logIfDebuggable("Should not show on application panel since task is not on allowed");
            return false;
        }
        return true;
    }

    private void cacheTask(ActivityManager.RunningTaskInfo taskInfo) {
        logIfDebuggable("Caching the task: " + taskInfo.taskId);
        if (mTaskInfoCache.cancelTask(taskInfo)) {
            boolean cached = mTaskInfoCache.cacheTask(taskInfo);
            logIfDebuggable("Task " + taskInfo.taskId + " is cached = " + cached);
        }
    }

    private void setControlBarSpacerVisibility(boolean isVisible) {
        if (mControlBarView == null) {
            return;
        }
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) mControlBarView.getLayoutParams();
        params.setMargins(/* left= */ 0, /* top= */ 0, /* right= */ 0,
                isVisible ? mNavBarHeight : 0);
        mControlBarView.requestLayout();
    }

    private void setHomeScreenBottomPadding(int bottomPadding) {
        // Set padding instead of margin so the bottom area shows background of
        // car_ui_portrait_launcher during immersive mode without nav bar, and panel states are
        // calculated correctly.
        mContainer.setPadding(/* left= */ 0, /* top= */ 0, /* right= */ 0, bottomPadding);
    }

    private void adjustFullscreenSpacing(boolean isFullscreen) {
        if (mIsSUWInProgress) {
            logIfDebuggable("don't change spaceing during suw");
            return;
        }
        logIfDebuggable(isFullscreen
                ? "Adjusting screen spacing for fullscreen task view"
                : "Adjusting screen spacing for non-fullscreen task views");
        setControlBarSpacerVisibility(isFullscreen);
        setHomeScreenBottomPadding(isFullscreen ? 0 : mNavBarHeight);
        // Add offset to background app area to avoid background apps shift for full screen app.
        mBackgroundAppArea.setPadding(/* left= */ 0, /* top= */ 0, /* right= */ 0,
                isFullscreen ? mNavBarHeight : 0);
    }

    // TODO(b/275633095): Add test to verify the region is set correctly in each mode
    private void updateObscuredTouchRegion() {
        logIfDebuggable("updateObscuredTouchRegion, mIsSUWInProgress=" + mIsSUWInProgress);
        Rect controlBarRect = new Rect();
        mControlBarView.getBoundsOnScreen(controlBarRect);

        Rect rootPanelGripBarRect = new Rect();
        mRootTaskViewPanel.getGripBarBounds(rootPanelGripBarRect);

        // Make the system bar bounds outside of display, so eventually the background and
        // fullscreen taskviews don't block the SUW
        int homescreenHeight = getWindow().getDecorView().getHeight();
        Rect navigationBarRect = new Rect(controlBarRect.left, /* top= */
                mIsSUWInProgress ? homescreenHeight : homescreenHeight - mNavBarHeight,
                controlBarRect.right,
                mIsSUWInProgress ? homescreenHeight + mNavBarHeight : homescreenHeight);

        Rect statusBarRect = new Rect(controlBarRect.left, /* top= */
                mIsSUWInProgress ? -mStatusBarHeight : 0,
                controlBarRect.right,
                mIsSUWInProgress ? 0 : mStatusBarHeight);

        Region obscuredTouchRegion = new Region();
        if (!mRootTaskViewPanel.isFullScreen()) {
            obscuredTouchRegion.union(controlBarRect);
        }
        obscuredTouchRegion.union(navigationBarRect);
        mTaskViewControllerWrapper.setObscuredTouchRegion(obscuredTouchRegion, APPLICATION);

        if (!mIsSUWInProgress) {
            obscuredTouchRegion.union(rootPanelGripBarRect);
            obscuredTouchRegion.union(statusBarRect);
        } else if (mRootTaskViewPanel.isVisible()) {
            obscuredTouchRegion.union(rootPanelGripBarRect);
        }
        logIfDebuggable("ObscuredTouchRegion, rootPanelGripBarRect = "
                + rootPanelGripBarRect + ", navigationBarRect = "
                + navigationBarRect + ", statusBarRect = "
                + statusBarRect + ", controlBarRect = "
                + controlBarRect);

        mTaskViewControllerWrapper.setObscuredTouchRegion(obscuredTouchRegion, BACKGROUND);
        mTaskViewControllerWrapper.setObscuredTouchRegion(obscuredTouchRegion, FULLSCREEN);
    }

    private void updateBackgroundTaskViewInsets() {
        if (mBackgroundAppArea == null) {
            return;
        }

        int bottomOverlap = mControlBarView.getTop();
        if (mRootTaskViewPanel.isVisible()) {
            bottomOverlap = mRootTaskViewPanel.getTop();
        }

        Rect appAreaBounds = new Rect();
        mBackgroundAppArea.getBoundsOnScreen(appAreaBounds);

        Rect bottomInsets = new Rect(appAreaBounds.left, bottomOverlap, appAreaBounds.right,
                appAreaBounds.bottom);
        Rect topInsets = new Rect(appAreaBounds.left, appAreaBounds.top, appAreaBounds.right,
                mStatusBarHeight);

        logIfDebuggable("Applying inset to backgroundTaskView bottom: " + bottomInsets + " top: "
                + topInsets);
        mTaskViewControllerWrapper.setSystemOverlayInsets(bottomInsets, topInsets, BACKGROUND);
    }

    private void updateFullScreenTaskViewInsets() {
        if (mFullScreenAppArea == null) {
            return;
        }

        Rect appAreaBounds = new Rect();
        mFullScreenAppArea.getBoundsOnScreen(appAreaBounds);

        Rect bottomInsets = new Rect(appAreaBounds.left, appAreaBounds.height() - mNavBarHeight,
                appAreaBounds.right, appAreaBounds.bottom);

        Rect topInsets = new Rect(appAreaBounds.left, appAreaBounds.top, appAreaBounds.right,
                mStatusBarHeight);

        logIfDebuggable("Applying inset to fullScreenTaskView bottom: " + bottomInsets + " top: "
                + topInsets);
        mTaskViewControllerWrapper.setSystemOverlayInsets(bottomInsets, topInsets, FULLSCREEN);
    }

    private void updateTaskViewInsets() {
        int bottom = mIsSUWInProgress ? 0 : mControlBarView.getHeight() + mNavBarHeight;
        Insets insets = Insets.of(/* left= */ 0, /* top= */ 0, /* right= */ 0, bottom);

        if (mRootTaskViewPanel != null) {
            mRootTaskViewPanel.setInsets(insets);
        }
        mTaskViewControllerWrapper.updateWindowBounds();
    }

    private void onContainerDimensionsChanged() {
        if (mRootTaskViewPanel != null) {
            mRootTaskViewPanel.onParentDimensionChanged();
        }

        updateTaskViewInsets();
        mTaskViewControllerWrapper.updateWindowBounds();
    }

    private void setUpBackgroundTaskView() {
        mBackgroundAppArea = findViewById(R.id.background_app_area);

        TaskViewControllerWrapper.TaskViewCallback callback =
                new TaskViewControllerWrapper.TaskViewCallback() {
                    @Override
                    public void onTaskViewCreated(@NonNull SurfaceView taskView) {
                        logIfDebuggable("Background Task View is created");
                        taskView.setZOrderOnTop(false);
                        mBackgroundAppArea.addView(taskView);
                        mTaskViewControllerWrapper.setTaskView(taskView, BACKGROUND);
                    }

                    @Override
                    public void onTaskViewInitialized() {
                        logIfDebuggable("Background Task View is ready");
                        mIsBackgroundTaskViewReady = true;
                        startBackgroundActivities();
                        onTaskViewReadinessUpdated();
                        updateBackgroundTaskViewInsets();
                        registerOnBackgroundApplicationInstallUninstallListener();
                    }

                    @Override
                    public void onTaskViewReleased() {
                        logIfDebuggable("Background Task View is released");
                        mTaskViewControllerWrapper.setTaskView(/* taskView= */ null, BACKGROUND);
                        mIsBackgroundTaskViewReady = false;
                    }
                };

        mTaskViewControllerWrapper.createCarRootTaskView(callback, getDisplayId(),
                getMainExecutor(), mTaskCategoryManager.getBackgroundActivitiesList());
    }

    private void startBackgroundActivities() {
        logIfDebuggable("start background activities");
        Intent backgroundIntent = mTaskCategoryManager.getCurrentBackgroundApp() == null
                ? CarLauncherUtils.getMapsIntent(getApplicationContext())
                : (new Intent()).setComponent(mTaskCategoryManager.getCurrentBackgroundApp());

        Intent failureRecoveryIntent = BackgroundPanelBaseActivity.createIntent(
                getApplicationContext());

        Intent[] intents = {failureRecoveryIntent, backgroundIntent};

        startActivitiesInternal(intents);

        // Set the background app here to avoid recreating
        // CarUiPortraitHomeScreen in onTaskCreated
        mTaskCategoryManager.setCurrentBackgroundApp(backgroundIntent.getComponent());
    }

    /** Starts given {@code intents} in order. */
    private void startActivitiesInternal(Intent[] intents) {
        for (Intent intent : intents) {
            startActivityInternal(intent);
        }
    }

    /** Starts given {@code intent}. */
    private void startActivityInternal(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to launch", e);
        }
    }

    private void registerOnBackgroundApplicationInstallUninstallListener() {
        mTaskCategoryManager.registerOnApplicationInstallUninstallListener(
                new TaskCategoryManager.OnApplicationInstallUninstallListener() {
                    @Override
                    public void onAppInstalled(String packageName) {
                        mTaskViewControllerWrapper.updateAllowListedActivities(BACKGROUND,
                                mTaskCategoryManager.getBackgroundActivitiesList());
                        mTaskViewControllerWrapper.updateAllowListedActivities(FULLSCREEN,
                                mTaskCategoryManager.getFullScreenActivitiesList());
                    }

                    @Override
                    public void onAppUninstall(String packageName) {
                        mTaskViewControllerWrapper.updateAllowListedActivities(BACKGROUND,
                                mTaskCategoryManager.getBackgroundActivitiesList());
                        mTaskViewControllerWrapper.updateAllowListedActivities(FULLSCREEN,
                                mTaskCategoryManager.getFullScreenActivitiesList());
                    }
                });
    }

    private void setControlBarVisibility(boolean isVisible, boolean animate) {
        float translationY = isVisible ? 0 : mContainer.getHeight() - mControlBarView.getTop();
        if (animate) {
            mControlBarView.animate().translationY(translationY).withEndAction(() -> {
                // TODO (b/316344351): Investigate why control bar does not re-appear
                //                     without requestLayout()
                mControlBarView.requestLayout();
                updateObscuredTouchRegion();
            });
        } else {
            mControlBarView.setTranslationY(translationY);
            updateObscuredTouchRegion();
        }
    }


    private boolean isAllTaskViewsReady() {
        return mRootTaskViewPanel.isReady()
                && mIsBackgroundTaskViewReady
                && mIsFullScreenTaskViewReady;
    }

    /**
     * Initialize non-default taskviews. Proceed after both {@link TaskViewControllerWrapper} and
     * {@code
     * mRootTaskViewPanel} are ready.
     *
     * <p>Note: 1. After flashing device and FRX, {@link UserEventReceiver} doesn't receive
     * {@link  Intent.ACTION_USER_UNLOCKED}, but PackageManager already starts
     * resolving intent right after {@code mRootTaskViewPanel} is ready. So initialize
     * {@link RemoteCarTaskView}s directly. 2. For device boot later, PackageManager starts to
     * resolving intent after {@link Intent.ACTION_USER_UNLOCKED}, so wait
     * until {@link UserEventReceiver} notify {@link CarUiPortraitHomeScreen}.
     */
    private void initTaskViews() {
        if (!mTaskCategoryManager.isReady() || !mRootTaskViewPanel.isReady()
                || isAllTaskViewsReady()) {
            return;
        }

        // BackgroundTaskView and FullScreenTaskView are init with activities lists provided by
        // mTaskCategoryManager. mTaskCategoryManager needs refresh to get up-to-date activities
        // lists.
        mTaskCategoryManager.refresh();
        setUpBackgroundTaskView();
        setUpFullScreenTaskView();
    }

    private void onTaskViewReadinessUpdated() {
        if (!isAllTaskViewsReady()) {
            return;
        }
        logIfDebuggable("All task views are ready");
        updateObscuredTouchRegion();
        updateBackgroundTaskViewInsets();
        notifySystemUI(MSG_FG_TASK_VIEW_READY, boolToInt(true));
        Rect controlBarBounds = new Rect();
        mControlBarView.getBoundsOnScreen(controlBarBounds);
        boolean isControlBarVisible = mControlBarView.getVisibility() == View.VISIBLE;
        logIfDebuggable("Control bar:" + "( visible: " + isControlBarVisible + ", bounds:"
                + controlBarBounds + ")");
        mTaskInfoCache.startCachedTasks();
    }

    private void setUpRootTaskView() {
        mRootTaskViewPanel.setTag("RootPanel");
        mRootTaskViewPanel.setOnStateChangeListener(new TaskViewPanel.OnStateChangeListener() {
            @Override
            public void onStateChangeStart(TaskViewPanel.State oldState,
                    TaskViewPanel.State newState, boolean animated,
                    TaskViewPanelStateChangeReason reason) {
                boolean isFullScreen = newState.isFullScreen();
                if (!mIsSUWInProgress) {
                    setControlBarVisibility(!isFullScreen, animated);
                    notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE,
                            isFullScreen ? WindowInsets.Type.navigationBars()
                                    : WindowInsets.Type.systemBars());
                }

                if (newState.isVisible() && newState != oldState) {
                    mTaskViewControllerWrapper.setWindowBounds(
                            mRootTaskViewPanel.getTaskViewBounds(newState), APPLICATION);
                }
                handleSystemBarButton(newState.isVisible());
            }

            @Override
            public void onStateChangeEnd(TaskViewPanel.State oldState, TaskViewPanel.State newState,
                    boolean animated, TaskViewPanelStateChangeReason reason) {
                updateObscuredTouchRegion();
                // Hide the control bar after the animation if in full screen.
                if (newState.isFullScreen()) {
                    setControlBarVisibility(/* isVisible= */ false, animated);
                    updateObscuredTouchRegion();
                } else {
                    // Update the background task view insets to make sure their content is not
                    // covered with our panels. We only need to do this when we are not in
                    // fullscreen.
                    updateBackgroundTaskViewInsets();
                }

                mTaskViewControllerWrapper.setWindowBounds(
                        mRootTaskViewPanel.getTaskViewBounds(newState), APPLICATION);

                if (!newState.isVisible()) {
                    startActivityInternal(CarLauncherUtils.getAppsGridIntent());
                    // Ensure the first click on AppGrid button can open the panel.
                    // When panel is closed for ON_PANEL_READY, the AppGrid get launched for the
                    // first time, it won't triggers OnRestartAttempt and reset
                    // mSkipAppGridOnRestartAttempt.
                    if (!ON_PANEL_READY.equals(reason.getReason())) {
                        mSkipAppGridOnRestartAttempt = true;
                    }
                }
            }
        });

        TaskViewControllerWrapper.TaskViewCallback callback =
                new TaskViewControllerWrapper.TaskViewCallback() {
                    @Override
                    public void onTaskViewCreated(@NonNull SurfaceView taskView) {
                        logIfDebuggable("Root Task View is created");
                        taskView.setZOrderMediaOverlay(true);
                        mRootTaskViewPanel.setTaskView(taskView);
                        mRootTaskViewPanel.setToolBarCallback(() -> sendVirtualBackPress());
                        mTaskViewControllerWrapper.setTaskView(taskView, APPLICATION);
                        mTaskViewControllerWrapper.updateWindowBounds();
                    }

                    @Override
                    public void onTaskViewInitialized() {
                        logIfDebuggable("Root Task View is ready");
                        mRootTaskViewPanel.setReady(true);
                        onTaskViewReadinessUpdated();
                        initTaskViews();
                    }

                    @Override
                    public void onTaskViewReleased() {
                        logIfDebuggable("Root Task View is released");
                        mTaskViewControllerWrapper.setTaskView(/* taskView= */ null, APPLICATION);
                        mRootTaskViewPanel.setReady(/* isReady= */ false);
                    }
                };

        mTaskViewControllerWrapper.createCarDefaultRootTaskView(callback, getDisplayId(),
                getMainExecutor());
    }

    private void setUpFullScreenTaskView() {
        mFullScreenAppArea = findViewById(R.id.fullscreen_container);
        TaskViewControllerWrapper.TaskViewCallback callback =
                new TaskViewControllerWrapper.TaskViewCallback() {
                    @Override
                    public void onTaskViewCreated(@NonNull SurfaceView taskView) {
                        logIfDebuggable("FullScreen Task View is created");
                        taskView.setZOrderOnTop(true);
                        mFullScreenAppArea.addView(taskView);
                        mTaskViewControllerWrapper.setTaskView(taskView, FULLSCREEN);
                    }

                    @Override
                    public void onTaskViewInitialized() {
                        logIfDebuggable("FullScreen Task View is ready");
                        mIsFullScreenTaskViewReady = true;
                        onTaskViewReadinessUpdated();
                        updateFullScreenTaskViewInsets();
                    }

                    @Override
                    public void onTaskViewReleased() {
                        logIfDebuggable("FullScreen Task View is released");
                        mTaskViewControllerWrapper.setTaskView(/* taskView= */ null, FULLSCREEN);
                        mIsFullScreenTaskViewReady = false;
                    }
                };
        mTaskViewControllerWrapper.createCarRootTaskView(callback, getDisplayId(),
                getMainExecutor(),
                mTaskCategoryManager.getFullScreenActivitiesList().stream().toList());
    }

    private void onImmersiveModeRequested(boolean requested, ComponentName componentName) {
        logIfDebuggable("onImmersiveModeRequested = " + requested + " cmp=" + componentName);
        if (mIsSUWInProgress) {
            logIfDebuggable("skip immersive change during suw");
            return;
        }

        if (mCarUiPortraitDriveStateController.isDrivingStateMoving()
                && mRootTaskViewPanel.isFullScreen()) {
            mRootTaskViewPanel.openPanel(createReason(ON_DRIVE_STATE_CHANGED, componentName));
            logIfDebuggable("Quit immersive mode due to drive mode");
            return;
        }

        // Only handles the immersive mode request here if requesting component has the same package
        // name as the current top task.
        if (!isPackageVisibleOnApplicationPanel(componentName)) {
            // Save the component and timestamp of the latest immersive mode request, in case any
            // race condition with TaskStackListener.
            setUnhandledImmersiveModeRequest(componentName, System.currentTimeMillis(), requested);
            logIfDebuggable("Set unhandle since the task is not at front");
            return;
        }

        if (requested) {
            mRootTaskViewPanel.openFullScreenPanel(/* animated= */ true, /* showToolBar= */ true,
                    mNavBarHeight, createReason(ON_IMMERSIVE_REQUEST, componentName));
        } else {
            mRootTaskViewPanel.openPanelWithIcon(createReason(ON_IMMERSIVE_REQUEST, componentName));
        }
    }

    private boolean isPackageVisibleOnApplicationPanel(ComponentName componentName) {
        TaskInfo taskInfo = mCurrentTaskInRootTaskView;
        logIfDebuggable("Top task in launch root task is" + taskInfo);
        if (taskInfo == null) {
            return false;
        }

        ComponentName visibleComponentName = getVisibleActivity(taskInfo);

        return visibleComponentName != null && componentName.getPackageName().equals(
                visibleComponentName.getPackageName());
    }

    private ComponentName getVisibleActivity(TaskInfo taskInfo) {
        if (taskInfo.topActivity != null) {
            return taskInfo.topActivity;
        } else if (taskInfo.baseActivity != null) {
            return taskInfo.baseActivity;
        } else {
            return taskInfo.baseIntent.getComponent();
        }
    }

    private void setUnhandledImmersiveModeRequest(ComponentName componentName, long timestamp,
            boolean requested) {
        mUnhandledImmersiveModeRequestComponent = componentName;
        mUnhandledImmersiveModeRequestTimestamp = timestamp;
        mUnhandledImmersiveModeRequest = requested;
    }

    private ComponentName getComponentNameFromBundle(Bundle bundle) {
        String cmpString = bundle.getString(INTENT_EXTRA_IMMERSIVE_MODE_REQUESTED_SOURCE);
        return (cmpString == null) ? null : ComponentName.unflattenFromString(cmpString);
    }

    void notifySystemUI(int key, int value) {
        if (mCarUiPortraitServiceManager != null) {
            logIfDebuggable("NotifySystemUI, key=" + key + ", value=" + value);
            mCarUiPortraitServiceManager.notifySystemUI(key, value);
        }
    }

    /**
     * Handler of incoming messages from service.
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_IMMERSIVE_MODE_REQUESTED:
                    onImmersiveModeRequested(intToBool(msg.arg1),
                            getComponentNameFromBundle(msg.getData()));
                    break;
                case MSG_SUW_IN_PROGRESS:
                    mIsSUWInProgress = intToBool(msg.arg1);
                    logIfDebuggable("Get intent about the SUW is " + mIsSUWInProgress);
                    if (mIsSUWInProgress) {
                        mRootTaskViewPanel.openFullScreenPanel(/* animated= */false,
                                /* showToolBar= */ false, /* bottomAdjustment= */ 0,
                                createReason(ON_SUW_STATE_CHANGED));
                    } else {
                        mRootTaskViewPanel.closePanel(createReason(ON_SUW_STATE_CHANGED));
                    }
                    break;
                case MSG_IMMERSIVE_MODE_CHANGE:
                    boolean hideNavBar = intToBool(msg.arg1);
                    mRootTaskViewPanel.setToolBarViewVisibility(hideNavBar);
                    break;
                case MSG_COLLAPSE_APPLICATION:
                    collapseAppPanel();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }
}
