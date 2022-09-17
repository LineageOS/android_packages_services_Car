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

import static android.view.InsetsState.ITYPE_BOTTOM_GENERIC_OVERLAY;
import static android.view.InsetsState.ITYPE_TOP_GENERIC_OVERLAY;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;
import android.widget.FrameLayout;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.CarLauncherUtils;
import com.android.car.carlauncher.CarTaskView;
import com.android.car.carlauncher.ControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.LaunchRootCarTaskViewCallbacks;
import com.android.car.carlauncher.TaskViewManager;
import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.portraitlauncher.R;
import com.android.wm.shell.common.HandlerExecutor;

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
 * |                             MAPS(Task View)                        |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * —--------------------------------------------------------------------
 * |                                                                    |
 * |                                                                    |
 * |                                                                    |
 * |                      App Space (Root Task View)                    |
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
 * In total this Activity has 2 TaskViews.
 * Background Task view:
 * - It only contains maps app.
 * - Maps app is manually started in this taskview.
 *
 * LowerTaskView:
 * - It acts as the default container. Which means all the apps will run inside it by default.
 *
 * Note: LowerTaskView always overlap over the Background TaskView.
 */
public final class CarUiPortraitHomeScreen extends FragmentActivity {
    public static final String TAG = "CarUiPortraitHomeScreen";
    private static final boolean DBG = Build.IS_DEBUGGABLE;

    private static final int ANIMATION_DURATION_MS = 300;

    private static final int STATE_OPEN = 1;
    private static final int STATE_CLOSE = 2;

    @IntDef({STATE_OPEN,
            STATE_CLOSE})
    private @interface RootAppAreaState {
    }

    @RootAppAreaState
    private int mRootAppAreaState = STATE_OPEN;
    private int mGripBarHeight;
    private int mStatusBarHeight;
    private int mBackgroundAppAreaHeightWhenCollapsed;
    private int mTitleBarDragThreshold;
    private FrameLayout mContainer;
    private View mRootAppAreaContainer;
    private View mGripBar;
    private View mControlBarView;
    private TaskViewManager mTaskViewManager;
    // All the TaskViews & corresponding helper instance variables.
    private CarTaskView mBackgroundTaskView;
    private CarTaskView mRootTaskView;
    private boolean mIsAnimating;
    private ComponentName mHomeActivityComponent;
    private ComponentName mBackgroundActivityComponent;
    private ComponentName mControlBarComponent;
    private ArraySet<ComponentName> mIgnoreOpeningRootTaskViewComponentsSet;
    private Set<HomeCardModule> mHomeCardModules;
    // contains the list of activities that will be displayed on feature {@link
    // CarDisplayAreaOrganizer.FEATURE_VOICE_PLATE)
    private Set<ComponentName> mVoicePlateActivitySet;
    // This listener lets us know when actives are added and removed from any of the display regions
    // we care about, so we can trigger the opening and closing of the app containers as needed.
    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            CarUiPortraitHomeScreen.this.updateRootTaskViewVisibility(taskInfo);
        }

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
            if (task.baseIntent == null || task.baseIntent.getComponent() == null
                    || mIsAnimating) {
                return;
            }

            if (!shouldTaskShowOnRootTaskView(task) || !wasVisible) {
                return;
            }

            // Toggle between STATE_OPEN and STATE_CLOSE when any task is in foreground and a new
            // Intent is sent to start the same task. In this case it's needed to toggle the root
            // task view when notification or AppGrid is in foreground and onClick on nav bar
            // buttons should close/open it.
            updateUIState(
                    mRootAppAreaState == STATE_OPEN ? STATE_CLOSE : STATE_OPEN,
                    /* animate = */ true);
        }
    };

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    void updateVoicePlateActivityMap() {
        Context currentUserContext = createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /* flags= */ 0);

        Intent voiceIntent = new Intent(Intent.ACTION_VOICE_ASSIST, /* uri= */ null);
        List<ResolveInfo> result = currentUserContext.getPackageManager().queryIntentActivities(
                voiceIntent, PackageManager.MATCH_ALL);

        for (ResolveInfo info : result) {
            if (mVoicePlateActivitySet.add(info.activityInfo.getComponentName()) && DBG) {
                logIfDebuggable("adding the following component to voice plate: "
                        + info.activityInfo.getComponentName());
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.car_ui_portrait_launcher);

        mGripBarHeight = (int) getResources().getDimension(R.dimen.grip_bar_height);
        mContainer = findViewById(R.id.container);
        mBackgroundAppAreaHeightWhenCollapsed =
                (int) getResources().getDimension(R.dimen.upper_app_area_collapsed_height);
        mTitleBarDragThreshold =
                (int) getResources().getDimension(
                        R.dimen.title_bar_display_area_touch_drag_threshold);
        mRootAppAreaContainer = findViewById(R.id.lower_app_area_container);
        mVoicePlateActivitySet = new ArraySet<>();
        mHomeActivityComponent = ComponentName.unflattenFromString(getResources().getString(
                R.string.config_homeActivity));
        mBackgroundActivityComponent = ComponentName.unflattenFromString(getResources().getString(
                R.string.config_backgroundActivity));

        mControlBarComponent = ComponentName.unflattenFromString(
                getResources().getString(
                        R.string.config_controlBarActivity));
        mGripBar = findViewById(R.id.grip_bar);
        mGripBar.setVisibility(GONE);

        mStatusBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);

        mControlBarView = findViewById(R.id.control_bar_area);
        String[] ignoreOpeningForegroundDACmp = getResources().getStringArray(
                R.array.config_ignoreOpeningForegroundDA);
        mIgnoreOpeningRootTaskViewComponentsSet = new ArraySet<>();
        for (String component : ignoreOpeningForegroundDACmp) {
            ComponentName componentName = ComponentName.unflattenFromString(component);
            mIgnoreOpeningRootTaskViewComponentsSet.add(componentName);
        }

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

        if (mTaskViewManager == null) {
            mTaskViewManager = new TaskViewManager(this,
                    new HandlerExecutor(getMainThreadHandler()));
        }
        ViewGroup upperAppArea = findViewById(R.id.upper_app_area);
        if (upperAppArea != null) {
            setUpUpperTaskView(upperAppArea);
        }

        ViewGroup lowerAppArea = findViewById(R.id.lower_app_area);
        if (lowerAppArea != null) {
            setUpRootTaskView(lowerAppArea);
        }

        findViewById(android.R.id.content).post(() ->
                updateUIState(STATE_CLOSE, /* animate = */ false));

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

        final float[] yValueWhenDownPressed = new float[1];
        final float[] lowerAppAreaContainerInitialYVal = new float[1];
        findViewById(R.id.grip_bar).setOnTouchListener(new View.OnTouchListener() {
            final FrameLayout.LayoutParams mLowerAppAreaParams =
                    (FrameLayout.LayoutParams) mRootAppAreaContainer.getLayoutParams();
            int mTopMargin = mLowerAppAreaParams.topMargin;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        yValueWhenDownPressed[0] = (int) event.getRawY();
                        lowerAppAreaContainerInitialYVal[0] = (int) mRootAppAreaContainer.getY();

                        onContainerDimensionsChanged();
                        mTopMargin = mLowerAppAreaParams.topMargin;

                        mGripBar.post(() -> updateBottomOverlap(STATE_OPEN));
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int currY = (int) event.getRawY();
                        mIsAnimating = true;
                        int diff = currY - (int) yValueWhenDownPressed[0];
                        if (diff < 0) {
                            diff = 0;
                        }

                        mLowerAppAreaParams.topMargin =
                                (int) lowerAppAreaContainerInitialYVal[0] + diff;

                        mRootAppAreaContainer.setLayoutParams(mLowerAppAreaParams);
                        break;
                    case MotionEvent.ACTION_UP:
                        float yEndVal = event.getRawY();
                        if (yEndVal > mContainer.getMeasuredHeight()) {
                            yEndVal = mContainer.getMeasuredHeight();
                        }

                        if (yEndVal > mTitleBarDragThreshold) {
                            mLowerAppAreaParams.topMargin = mContainer.getMeasuredHeight();
                            mRootAppAreaState = STATE_CLOSE;
                            mRootAppAreaContainer.setLayoutParams(mLowerAppAreaParams);
                        } else {
                            mLowerAppAreaParams.topMargin = mTopMargin;
                            mRootAppAreaState = STATE_OPEN;
                            mRootAppAreaContainer.setLayoutParams(mLowerAppAreaParams);
                        }
                        mRootTaskView.setZOrderOnTop(false);
                        mIsAnimating = false;
                        // Do this on animation end
                        mGripBar.post(() -> updateBottomOverlap(mRootAppAreaState));
                        break;
                    default:
                        return false;
                }
                return true;
            }
        });

        updateVoicePlateActivityMap();
        initializeCards();
    }

    private void initializeCards() {
        if (mHomeCardModules == null) {
            mHomeCardModules = new androidx.collection.ArraySet<>();
            for (String providerClassName : getResources().getStringArray(
                    R.array.config_homeCardModuleClasses)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule) Class.forName(
                            providerClassName).newInstance();
                    cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */this));
                    mHomeCardModules.add(cardModule);
                    if (DBG) {
                        long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                        Log.d(TAG, "Initialization of HomeCardModule class " + providerClassName
                                + " took " + reflectionTime + " ms");
                    }
                } catch (IllegalAccessException | InstantiationException
                         | ClassNotFoundException e) {
                    Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
                }
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : mHomeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView());
        }
        transaction.commitNow();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initializeCards();
        refreshGrabBar();
    }

    private void refreshGrabBar() {
        Drawable background = getResources().getDrawable(R.drawable.title_bar_background);
        mGripBar.setBackground(background);
    }

    @Override
    protected void onDestroy() {
        mTaskViewManager = null;
        mRootTaskView = null;
        mBackgroundTaskView = null;
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        super.onDestroy();
    }

    private void updateRootTaskViewVisibility(TaskInfo taskInfo) {
        if (!shouldTaskShowOnRootTaskView(taskInfo)) {
            return;
        }

        ComponentName componentName = taskInfo.baseIntent.getComponent();

        // check if the foreground DA is visible to the user. If not, make it visible.
        if (mRootAppAreaState == STATE_CLOSE) {
            logIfDebuggable("opening DA on request for cmp: " + componentName);
            updateUIState(STATE_OPEN, /* animate = */ true);
        }
        // just let the task launch and don't change the state of the foreground DA.
    }

    private boolean shouldTaskShowOnRootTaskView(TaskInfo taskInfo) {
        if (taskInfo.baseIntent == null || taskInfo.baseIntent.getComponent() == null
                || mIsAnimating) {
            return false;
        }

        ComponentName componentName = taskInfo.baseIntent.getComponent();
        // Voice plate will be shown as the top most layer. Also, we don't want to change the
        // state of the DA's when voice plate is shown.
        boolean isVoicePlate = mVoicePlateActivitySet.contains(componentName);
        if (isVoicePlate) {
            return false;
        }

        boolean isControlBar = componentName.equals(mControlBarComponent);
        boolean isBackgroundApp = mBackgroundActivityComponent.equals(componentName);
        boolean isHomeActivity = componentName.equals(mHomeActivityComponent);

        if (isBackgroundApp) {
            // we don't want to change the state of the foreground DA when background
            // apps are launched.
            return false;
        }

        if (isHomeActivity && (mRootAppAreaState != STATE_CLOSE)) {
            // close the foreground DA
            updateUIState(STATE_CLOSE, /* animate = */ true);
            return false;
        }

        if (isControlBar) {
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
                && taskInfo.userId == ActivityManager.getCurrentUser()
                && !shouldIgnoreOpeningForegroundDA(taskInfo)
                && !isHomeActivity;
    }

    boolean shouldIgnoreOpeningForegroundDA(TaskInfo taskInfo) {
        return taskInfo.baseIntent != null && mIgnoreOpeningRootTaskViewComponentsSet.contains(
                taskInfo.baseIntent.getComponent());
    }

    private void updateUIState(int newLowerAppAreaState, boolean animate) {
        int lowerAppAreaTop;
        Runnable onAnimationEnd;

        if (newLowerAppAreaState == STATE_OPEN) {
            lowerAppAreaTop = mBackgroundAppAreaHeightWhenCollapsed - mGripBarHeight;

            // Animate the lower app area first and then change the upper app area size to avoid
            // black patch on screen.
            onAnimationEnd = () -> {
                mGripBar.post(() -> updateBottomOverlap(STATE_OPEN));
                mRootAppAreaState = STATE_OPEN;
                mRootTaskView.setZOrderOnTop(false);
                mIsAnimating = false;
                mGripBar.setVisibility(VISIBLE);
            };
        } else {
            lowerAppAreaTop = mContainer.getMeasuredHeight();

            // Change the upper app area's size to full-screen first and then animate the lower app
            // area to avoid black patch on screen.
            mGripBar.post(() -> updateBottomOverlap(STATE_CLOSE));

            onAnimationEnd = () -> {
                mRootAppAreaState = STATE_CLOSE;
                mRootTaskView.setZOrderOnTop(false);
                mIsAnimating = false;
            };
        }

        if (animate) {
            mIsAnimating = true;
            Animation animation = createAnimationForLowerAppArea(lowerAppAreaTop /* newTop */,
                    onAnimationEnd);
            mRootAppAreaContainer.startAnimation(animation);
        } else {
            FrameLayout.LayoutParams lowerAppAreaParams =
                    (FrameLayout.LayoutParams) mRootAppAreaContainer.getLayoutParams();
            if (newLowerAppAreaState == STATE_OPEN) {
                // Update the height only for the open state because for the closed state, it
                // is anyhow not visible.
                // Triggering height change when it is off-screen sometimes gives problems.
                lowerAppAreaParams.height = mContainer.getMeasuredHeight()
                        - mBackgroundAppAreaHeightWhenCollapsed + mGripBarHeight;
            }
            lowerAppAreaParams.topMargin = lowerAppAreaTop;

            mRootAppAreaContainer.setLayoutParams(lowerAppAreaParams);

            onAnimationEnd.run();
        }
    }

    private Animation createAnimationForLowerAppArea(int newTop, Runnable onAnimationEnd) {
        FrameLayout.LayoutParams lowerAppAreaParams =
                (FrameLayout.LayoutParams) mRootAppAreaContainer.getLayoutParams();
        Animation animation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                lowerAppAreaParams.topMargin =
                        lowerAppAreaParams.topMargin - (int) ((lowerAppAreaParams.topMargin
                                - newTop) * interpolatedTime);
                mRootAppAreaContainer.setLayoutParams(lowerAppAreaParams);
            }
        };
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                onAnimationEnd.run();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });

        animation.setInterpolator(new DecelerateInterpolator());
        animation.setDuration(ANIMATION_DURATION_MS);
        return animation;
    }

    private void updateBottomOverlap(int newLowerAppAreaState) {
        if (mBackgroundTaskView == null) {
            return;
        }

        Rect upperAppAreaBounds = new Rect();
        mBackgroundTaskView.getBoundsOnScreen(upperAppAreaBounds);

        Rect gripBarBounds = new Rect();
        mGripBar.getBoundsOnScreen(gripBarBounds);

        Region obscuredRegion = new Region(gripBarBounds.left, gripBarBounds.top,
                gripBarBounds.right, gripBarBounds.bottom);
        Rect controlBarBounds = new Rect();
        mControlBarView.getBoundsOnScreen(controlBarBounds);
        obscuredRegion.union(controlBarBounds);

        // Use setObscuredTouchRect on all the taskviews that overlap with the grip bar.
        mBackgroundTaskView.setObscuredTouchRegion(obscuredRegion);
        if (newLowerAppAreaState == STATE_OPEN) {
            applyBottomInsetsToUpperTaskView(mRootAppAreaContainer.getHeight(),
                    upperAppAreaBounds);
        } else {
            applyBottomInsetsToUpperTaskView(controlBarBounds.height(), upperAppAreaBounds);
        }
    }

    private void applyBottomInsetsToUpperTaskView(int bottomOverlap, Rect appAreaBounds) {

        mBackgroundTaskView.setInsets(new SparseArray<Rect>() {
            {
                append(ITYPE_BOTTOM_GENERIC_OVERLAY, new Rect(appAreaBounds.left,
                        appAreaBounds.bottom - bottomOverlap,
                        appAreaBounds.right,
                        appAreaBounds.bottom
                ));
                append(ITYPE_TOP_GENERIC_OVERLAY, new Rect(appAreaBounds.left,
                        appAreaBounds.top,
                        appAreaBounds.right,
                        mStatusBarHeight
                ));
            }
        });
    }

    private void onContainerDimensionsChanged() {
        // Call updateUIState for the current state to recalculate the UI
        updateUIState(mRootAppAreaState, /* animate = */ false);
        if (mBackgroundTaskView != null) {
            mBackgroundTaskView.post(() -> mBackgroundTaskView.onLocationChanged());
        }
        if (mRootTaskView != null) {
            mRootTaskView.post(() -> mRootTaskView.onLocationChanged());
        }
    }

    private void setUpUpperTaskView(ViewGroup parent) {
        mTaskViewManager.createControlledCarTaskView(getMainExecutor(),
                CarLauncherUtils.getMapsIntent(getApplicationContext()),
                true,
                new ControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        mBackgroundTaskView = taskView;
                        parent.addView(mBackgroundTaskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                    }
                }
        );
    }

    private void setUpRootTaskView(ViewGroup parent) {
        mTaskViewManager.createLaunchRootTaskView(getMainExecutor(),
                new LaunchRootCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        mRootTaskView = taskView;
                        parent.addView(mRootTaskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                    }
                });
    }
}
