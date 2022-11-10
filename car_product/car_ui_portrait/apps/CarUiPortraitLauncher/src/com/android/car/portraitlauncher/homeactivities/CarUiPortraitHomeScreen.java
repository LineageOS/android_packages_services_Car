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
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_FG_TASK_VIEW_READY;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_IMMERSIVE_MODE_REQUESTED;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_REGISTER_CLIENT;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_ROOT_TASK_VIEW_VISIBILITY_CHANGE;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_SUW_IN_PROGRESS;
import static com.android.car.caruiportrait.common.service.CarUiPortraitService.MSG_UNREGISTER_CLIENT;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
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
import android.widget.ImageView;

import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.CarLauncherUtils;
import com.android.car.carlauncher.CarTaskView;
import com.android.car.carlauncher.ControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.LaunchRootCarTaskViewCallbacks;
import com.android.car.carlauncher.SemiControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.TaskViewManager;
import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.caruiportrait.common.service.CarUiPortraitService;
import com.android.car.portraitlauncher.R;

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
    private static final int STATE_FULL = 3;

    @IntDef({STATE_OPEN,
            STATE_CLOSE,
            STATE_FULL})
    private @interface RootAppAreaState {
    }

    @RootAppAreaState
    private int mRootAppAreaState = STATE_CLOSE;
    private int mGripBarHeight;
    private int mStatusBarHeight;
    private int mBackgroundAppAreaHeightWhenCollapsed;
    private int mTitleBarDragThreshold;
    private FrameLayout mContainer;
    private View mRootAppAreaContainer;
    private View mGripBar;
    private View mGripBarView;
    private ImageView mImmersiveButtonView;
    private boolean mIsRootTaskViewFullScreen;
    private boolean mShouldSetInsetsOnUpperTaskView;
    private Drawable mChevronUpDrawable;
    private Drawable mChevronDownDrawable;
    private View mControlBarView;
    private TaskViewManager mTaskViewManager;
    // All the TaskViews & corresponding helper instance variables.
    private CarTaskView mBackgroundTaskView;
    private CarTaskView mFullScreenTaskView;
    public Set<ComponentName> mFullScreenActivities;
    public Set<ComponentName> mDrawerActivities;
    private CarTaskView mRootTaskView;
    private boolean mIsAnimating;
    private ComponentName mBackgroundActivityComponent;
    private ArraySet<ComponentName> mIgnoreOpeningRootTaskViewComponentsSet;
    private Set<HomeCardModule> mHomeCardModules;
    private boolean mIsLowerPanelInitialized;
    private int mNavBarHeight;
    private int mControlBarHeightMinusCornerRadius;
    private int mCornerRadius;

    /** Messenger for communicating with {@link CarUiPortraitService}. */
    private Messenger mService = null;
    /** Flag indicating whether or not {@link CarUiPortraitService} is bounded. */
    private boolean mIsBound;

    /**
     * All messages from {@link CarUiPortraitService} are received in this handler.
     */
    private final Messenger mMessenger = new Messenger(new IncomingHandler());

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

            if (!wasVisible) {
                return;
            }

            if (mBackgroundActivityComponent.equals(task.baseActivity)) {
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
            if (mFullScreenActivities.add(info.activityInfo.getComponentName()) && DBG) {
                logIfDebuggable("adding the following component to show on fullscreen: "
                        + info.activityInfo.getComponentName());
            }
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.car_ui_portrait_launcher);
        // Make the window fullscreen as GENERIC_OVERLAYS are supplied to the background task view
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Activity is running fullscreen to allow backgroound task to bleed behind status bar
        int identifier = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        mNavBarHeight = identifier > 0 ? getResources().getDimensionPixelSize(identifier) : 0;
        mGripBarHeight = (int) getResources().getDimension(R.dimen.grip_bar_height);
        mControlBarHeightMinusCornerRadius = (int) getResources().getDimension(
                R.dimen.control_bar_height_minus_corner_radius);
        mCornerRadius = (int) getResources().getDimension(R.dimen.corner_radius);
        mContainer = findViewById(R.id.container);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContainer.getLayoutParams();
        lp.bottomMargin = mNavBarHeight;
        mContainer.setLayoutParams(lp);
        mBackgroundAppAreaHeightWhenCollapsed =
                (int) getResources().getDimension(R.dimen.upper_app_area_collapsed_height);
        mTitleBarDragThreshold =
                (int) getResources().getDimension(
                        R.dimen.title_bar_display_area_touch_drag_threshold);
        mRootAppAreaContainer = findViewById(R.id.lower_app_area_container);
        mBackgroundActivityComponent = ComponentName.unflattenFromString(getResources().getString(
                R.string.config_backgroundActivity));
        mFullScreenActivities = convertToComponentNames(getResources()
                .getStringArray(R.array.config_fullScreenActivities));
        mDrawerActivities = convertToComponentNames(getResources()
                .getStringArray(R.array.config_drawerActivities));
        mShouldSetInsetsOnUpperTaskView = getResources().getBoolean(
                R.bool.config_setInsetsOnUpperTaskView);
        mGripBar = findViewById(R.id.grip_bar);
        mGripBarView = findViewById(R.id.grip_bar_view);
        mGripBar.setVisibility(GONE);

        mChevronUpDrawable = getDrawable(R.drawable.ic_chevron_up);
        mChevronDownDrawable = getDrawable(R.drawable.ic_chevron_down);

        mImmersiveButtonView = findViewById(R.id.immersive_button);
        if (mImmersiveButtonView != null) {
            mImmersiveButtonView.setImageDrawable(
                    mIsRootTaskViewFullScreen ? mChevronDownDrawable
                            : mChevronUpDrawable);
            mImmersiveButtonView.setOnClickListener(v -> {
                if (mIsRootTaskViewFullScreen) {
                    // notify systemUI to show bars
                    notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE, boolToInt(false));
                    mIsRootTaskViewFullScreen = false;
                    updateUIState(STATE_OPEN, true);
                    mControlBarView.setVisibility(VISIBLE);
                } else {
                    // notify systemUI to hide bars
                    notifySystemUI(MSG_HIDE_SYSTEM_BAR_FOR_IMMERSIVE, boolToInt(true));
                    mControlBarView.setVisibility(INVISIBLE);
                    mIsRootTaskViewFullScreen = true;
                }
                mImmersiveButtonView.setImageDrawable(
                        mIsRootTaskViewFullScreen ? mChevronDownDrawable
                                : mChevronUpDrawable);
            });
        }
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
            mTaskViewManager = new TaskViewManager(this, getMainThreadHandler());
        }

        ViewGroup lowerAppArea = findViewById(R.id.lower_app_area);
        if (lowerAppArea != null) {
            setUpRootTaskView(lowerAppArea);
        }

        ViewGroup upperAppArea = findViewById(R.id.upper_app_area);
        if (upperAppArea != null) {
            setUpBackgroundTaskView(upperAppArea);
        }

        ViewGroup fullscreenContainer = findViewById(R.id.fullscreen_container);
        if (fullscreenContainer != null) {
            setUpFullScreenTaskView(fullscreenContainer);
        }

        requireViewById(android.R.id.content).post(() -> {
            resetRootTaskViewToDefaultHeight();
            updateUIState(STATE_CLOSE, /* animate = */ false);
        });

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
                            notifySystemUI(MSG_ROOT_TASK_VIEW_VISIBILITY_CHANGE, boolToInt(false));
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
        doBindService();
    }

    private static ArraySet<ComponentName> convertToComponentNames(String[] componentStrings) {
        ArraySet<ComponentName> componentNames = new ArraySet<>(componentStrings.length);
        for (int i = componentStrings.length - 1; i >= 0; i--) {
            componentNames.add(ComponentName.unflattenFromString(componentStrings[i]));
        }
        return componentNames;
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
        mFullScreenTaskView = null;
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        doUnbindService();
        super.onDestroy();
    }

    private void updateRootTaskViewVisibility(TaskInfo taskInfo) {
        logIfDebuggable("task moved to front: " + taskInfo);
        if (!shouldTaskShowOnRootTaskView(taskInfo)) {
            return;
        }
        logIfDebuggable("sowing task in task view: ");
        // check if the foreground DA is visible to the user. If not, make it visible.
        if (mRootAppAreaState == STATE_CLOSE) {
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

        // fullscreen activities will open in a separate task view which will show on top most
        // z-layer, that should not change the state of the root task view.
        if (mFullScreenActivities.contains(taskInfo.baseActivity)) {
            return false;
        }

        if (mDrawerActivities.contains(taskInfo.baseActivity)) {
            return false;
        }

        boolean isBackgroundApp = mBackgroundActivityComponent.equals(componentName);
        if (isBackgroundApp) {
            // we don't want to change the state of the root task view when background
            // task are launched or brought to front.
            return false;
        }

        // Any task that does NOT meet all the below criteria should be ignored.
        // 1. displayAreaFeatureId should be FEATURE_DEFAULT_TASK_CONTAINER
        // 2. should be visible
        // 3. for the current user ONLY. System user launches some tasks on cluster that should
        //    not affect the state of the foreground DA
        // 4. any task that is manually defined to be ignored
        return taskInfo.displayAreaFeatureId == FEATURE_DEFAULT_TASK_CONTAINER
                && taskInfo.userId == ActivityManager.getCurrentUser()
                && !shouldIgnoreOpeningForegroundDA(taskInfo);
    }

    boolean shouldIgnoreOpeningForegroundDA(TaskInfo taskInfo) {
        return taskInfo.baseIntent != null && mIgnoreOpeningRootTaskViewComponentsSet.contains(
                taskInfo.baseIntent.getComponent());
    }

    private void updateUIState(int newLowerAppAreaState, boolean animate) {
        logIfDebuggable("updating UI state to: " + newLowerAppAreaState);
        if (!mIsLowerPanelInitialized) {
            return;
        }
        int lowerAppAreaTop = 0;
        Runnable onAnimationEnd = null;
        if (newLowerAppAreaState == STATE_OPEN) {
            lowerAppAreaTop = mBackgroundAppAreaHeightWhenCollapsed - mGripBarHeight;

            // Animate the lower app area first and then change the upper app area size to avoid
            // black patch on screen.
            onAnimationEnd = () -> {
                notifySystemUI(MSG_ROOT_TASK_VIEW_VISIBILITY_CHANGE, boolToInt(true));
                mGripBar.post(() -> updateBottomOverlap(STATE_OPEN));
                mRootAppAreaState = STATE_OPEN;
                mRootTaskView.setZOrderOnTop(false);
                mIsAnimating = false;
                mGripBar.setVisibility(VISIBLE);
            };
        } else if (newLowerAppAreaState == STATE_CLOSE) {
            lowerAppAreaTop = mContainer.getMeasuredHeight();

            // Change the upper app area's size to full-screen first and then animate the lower app
            // area to avoid black patch on screen.
            mGripBar.post(() -> updateBottomOverlap(STATE_CLOSE));

            onAnimationEnd = () -> {
                notifySystemUI(MSG_ROOT_TASK_VIEW_VISIBILITY_CHANGE, boolToInt(false));
                mRootAppAreaState = STATE_CLOSE;
                mRootTaskView.setZOrderOnTop(false);
                mIsAnimating = false;
            };
        } else {
            // newLowerAppAreaState == STATE_FULL
            lowerAppAreaTop = 0;

            // Animate the lower app area first and then change the upper app area size to avoid
            // black patch on screen.
            onAnimationEnd = () -> {
                mGripBar.post(() -> updateBottomOverlap(STATE_FULL));
                mRootAppAreaState = STATE_FULL;
                mIsAnimating = false;
            };
        }

        if (animate) {
            mIsAnimating = true;
            Animation animation = null;
            if (newLowerAppAreaState == STATE_FULL) {
                animation = createImmersiveAnimationForLowerAppArea(lowerAppAreaTop /* newTop */,
                        onAnimationEnd);
            } else if (mRootAppAreaState == STATE_FULL && newLowerAppAreaState == STATE_OPEN) {
                animation = createImmersiveAnimationForLowerAppArea(lowerAppAreaTop /* newTop */,
                        onAnimationEnd);
            } else {
                animation = createAnimationForLowerAppArea(lowerAppAreaTop /* newTop */,
                        onAnimationEnd);
            }

            mRootAppAreaContainer.startAnimation(animation);
        } else {
            if (newLowerAppAreaState == STATE_OPEN) {
                // Update the height only for the open state because for the closed state, it
                // is anyhow not visible.
                // Triggering height change when it is off-screen sometimes gives problems.
                if (!mIsRootTaskViewFullScreen) {
                    resetRootTaskViewToDefaultHeight();
                } else {
                    makeRootTaskViewFullscreen();
                }
            }

            onAnimationEnd.run();
        }
    }

    private void makeRootTaskViewFullscreen() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)
                mRootAppAreaContainer.getLayoutParams();

        // Set margin and padding to 0 so the SUW shows full screen
        lp.height = mContainer.getMeasuredHeight();
        lp.topMargin = 0;
        mRootAppAreaContainer.setLayoutParams(lp);
        mRootAppAreaContainer.setPadding(0, 0, 0, 0);
        mControlBarView.setVisibility(INVISIBLE);
    }

    private void resetRootTaskViewToDefaultHeight() {
        int lowerAppAreaTop = mBackgroundAppAreaHeightWhenCollapsed - mGripBarHeight;

        FrameLayout.LayoutParams lowerAppAreaParams =
                (FrameLayout.LayoutParams) mRootAppAreaContainer.getLayoutParams();
        lowerAppAreaParams.height = mContainer.getMeasuredHeight()
                - mBackgroundAppAreaHeightWhenCollapsed + mGripBarHeight;
        lowerAppAreaParams.topMargin = lowerAppAreaTop;

        mRootAppAreaContainer.setLayoutParams(lowerAppAreaParams);

        // Set bottom padding to be height of controller bar, so they won't overlap
        int rootAppAreaContainerBottomPadding =
                getApplicationContext().getResources().getDimensionPixelSize(
                        R.dimen.control_bar_height);
        mRootAppAreaContainer.setPadding(0, 0, 0, rootAppAreaContainerBottomPadding);

        mControlBarView.setVisibility(VISIBLE);
        mIsLowerPanelInitialized = true;
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

    private Animation createImmersiveAnimationForLowerAppArea(int newTop, Runnable onAnimationEnd) {
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
        if (newLowerAppAreaState != STATE_FULL) {
            mControlBarView.getBoundsOnScreen(controlBarBounds);
            obscuredRegion.union(controlBarBounds);
        }

        // Use setObscuredTouchRect on all the taskviews that overlap with the grip bar.
        mBackgroundTaskView.setObscuredTouchRegion(obscuredRegion);
        mFullScreenTaskView.setObscuredTouchRegion(obscuredRegion);
        if (newLowerAppAreaState == STATE_OPEN) {
            // Set control bar bounds as obscured region on RootTaskview when AppGrid launcher is
            // open.
            mRootTaskView.setObscuredTouchRect(controlBarBounds);
            applyBottomInsetsToUpperTaskView(
                    mRootAppAreaContainer.getHeight() - mControlBarHeightMinusCornerRadius,
                    upperAppAreaBounds);
        } else if (newLowerAppAreaState == STATE_FULL) {
            mRootTaskView.setObscuredTouchRect(null);
            applyBottomInsetsToUpperTaskView(mNavBarHeight, upperAppAreaBounds);
        } else {
            applyBottomInsetsToUpperTaskView(mCornerRadius, upperAppAreaBounds);
        }
    }

    private void applyBottomInsetsToUpperTaskView(int bottomOverlap, Rect appAreaBounds) {

        if (mShouldSetInsetsOnUpperTaskView) {
            Rect bottomInsets = new Rect(appAreaBounds.left, appAreaBounds.bottom - bottomOverlap,
                    appAreaBounds.right, appAreaBounds.bottom);
            Rect topInsets = new Rect(appAreaBounds.left, appAreaBounds.top, appAreaBounds.right,
                    mStatusBarHeight);

            logIfDebuggable(
                    "Applying bottom insets: " + bottomInsets + " top insets: " + topInsets);
            mBackgroundTaskView.setInsets(new SparseArray<Rect>() {
                {
                    append(ITYPE_BOTTOM_GENERIC_OVERLAY, bottomInsets);
                    append(ITYPE_TOP_GENERIC_OVERLAY, topInsets);
                }
            });
        }
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

    private void setUpBackgroundTaskView(ViewGroup parent) {
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
                        logIfDebuggable("Foreground Task View is ready");
                        notifySystemUI(MSG_FG_TASK_VIEW_READY, boolToInt(true));
                    }
                });
    }

    private void setUpFullScreenTaskView(ViewGroup parent) {
        mTaskViewManager.createSemiControlledTaskView(getMainExecutor(),
                new SemiControlledCarTaskViewCallbacks() {
                    @Override
                    public boolean shouldStartInTaskView(TaskInfo taskInfo) {
                        if (taskInfo.baseActivity == null) {
                            return false;
                        }
                        return mFullScreenActivities.contains(taskInfo.baseActivity);
                    }

                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        mFullScreenTaskView = taskView;
                        mFullScreenTaskView.setZOrderOnTop(true);
                        parent.addView(mFullScreenTaskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                    }
                });
    }

    private void onImmersiveModeRequested(boolean requested) {
        if (mGripBarView != null) {
            mGripBarView.setVisibility(requested ? View.GONE : View.VISIBLE);
        }
        if (mImmersiveButtonView != null) {
            mImmersiveButtonView.setVisibility(requested ? View.VISIBLE : View.GONE);
        }
        if (!requested) {
            resetRootTaskViewToDefaultHeight();
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
                    onImmersiveModeRequested(intToBool(msg.arg1));
                    break;
                case MSG_SUW_IN_PROGRESS:
                    boolean isSuwInProgress = intToBool(msg.arg1);
                    if (isSuwInProgress) {
                        makeRootTaskViewFullscreen();
                    } else {
                        resetRootTaskViewToDefaultHeight();
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    void doBindService() {
        // Establish a connection with {@link CarUiPortraitService}. We use an explicit class
        // name because there is no reason to be able to let other applications replace our
        // component.
        bindService(new Intent(this, CarUiPortraitService.class), mConnection,
                Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    void doUnbindService() {
        if (mIsBound) {
            if (mService != null) {
                try {
                    Message msg = Message.obtain(null, MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    Log.w(TAG, "can't unregister to CarUiPortraitService: ", e);
                }
            }

            // Detach our existing connection.
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    private void notifySystemUI(int key, int value) {
        Message msg = Message.obtain(null, key, value, 0);
        try {
            if (mService != null) {
                mService.send(msg);
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static int boolToInt(Boolean b) {
        return b ? 1 : 0;
    }

    private static boolean intToBool(int val) {
        return val == 1;
    }
}
