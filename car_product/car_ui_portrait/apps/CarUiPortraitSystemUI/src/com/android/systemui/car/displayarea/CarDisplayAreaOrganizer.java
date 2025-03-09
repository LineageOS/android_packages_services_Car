/*
 * Copyright (C) 2024 The Android Open Source Project.
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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.car.displayarea.DisplayAreaComponent.CONTROL_BAR;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.DEFAULT;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.FULL;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.FULL_TO_DEFAULT;

import android.annotation.NonNull;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.systemui.R;
import com.android.systemui.car.displayarea.DisplayAreaComponent.PanelState;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Organizer for controlling the policies defined in
 * {@link com.android.server.wm.CarDisplayAreaPolicyProvider}
 */
public class CarDisplayAreaOrganizer extends DisplayAreaOrganizer {
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;
    private static final String TAG = CarDisplayAreaOrganizer.class.getSimpleName();

    /**
     * The display partition to launch applications by default.
     */
    public static final int FOREGROUND_DISPLAY_AREA_ROOT = FEATURE_VENDOR_FIRST + 1;
    /**
     * Background applications task container.
     */
    public static final int BACKGROUND_TASK_CONTAINER = FEATURE_VENDOR_FIRST + 2;
    public static final int FEATURE_TASKDISPLAYAREA_PARENT = FEATURE_VENDOR_FIRST + 3;
    /**
     * Control bar task container.
     */
    public static final int CONTROL_BAR_DISPLAY_AREA = FEATURE_VENDOR_FIRST + 4;
    public static final int FEATURE_TITLE_BAR = FEATURE_VENDOR_FIRST + 5;
    static final int FEATURE_VOICE_PLATE = FEATURE_VENDOR_FIRST + 6;

    private final ComponentName mControlBarActivityName;
    private final List<ComponentName> mBackGroundActivities;
    private final int mTitleBarViewHeight;
    private final Context mContext;
    private final int mTotalScreenHeightWithoutNavBar;
    private final int mTotalScreenWidth;
    private final SyncTransactionQueue mTransactionQueue;
    private final Rect mForegroundApplicationDisplayBounds = new Rect();
    private final Rect mBackgroundApplicationDisplayBounds = new Rect();
    private final Rect mTitleBarDisplayBounds = new Rect();
    private final CarDisplayAreaAnimationController mAnimationController;
    private final Handler mHandlerForAnimation;
    private final Binder mInsetsOwner = new Binder();

    private DisplayAreaAnimationRunnable mDisplayAreaAnimationRunnable = null;
    private WindowContainerToken mBackgroundDisplayToken;
    private WindowContainerToken mForegroundDisplayToken;
    private int mDpiDensity = -1;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private DisplayAreaAppearedInfo mForegroundApplicationDisplay;
    private DisplayAreaAppearedInfo mControlBarDisplay;
    private DisplayAreaAppearedInfo mVoicePlateDisplay;
    private boolean mIsRegistered = false;
    private boolean mIsDisplayAreaAnimating = false;
    private @PanelState int mCurrentState;
    private int mControlBarHeight;

    private final ArrayMap<WindowContainerToken, SurfaceControl> mDisplayAreaTokenMap =
            new ArrayMap();
    private final Car.CarServiceLifecycleListener mCarServiceLifecycleListener =
            new Car.CarServiceLifecycleListener() {
                @Override
                public void onLifecycleChanged(@NonNull Car car, boolean ready) {
                    if (ready) {
                        CarActivityManager carAm = (CarActivityManager) car.getCarManager(
                                Car.CAR_ACTIVITY_SERVICE);
                        for (ComponentName backgroundCmp : mBackGroundActivities) {
                            CarDisplayAreaUtils.setPersistentActivity(carAm, backgroundCmp,
                                    BACKGROUND_TASK_CONTAINER, "Background");
                        }
                        CarDisplayAreaUtils.setPersistentActivity(carAm, mControlBarActivityName,
                                CONTROL_BAR_DISPLAY_AREA, "ControlBar");
                    }
                }
            };

    private CarDisplayAreaAnimationCallback mDisplayAreaAnimationCallback =
            new CarDisplayAreaAnimationCallback() {
                @Override
                public void onAnimationStart(
                        CarDisplayAreaAnimationController
                                .CarDisplayAreaTransitionAnimator animator) {

                    mIsDisplayAreaAnimating = true;
                }

                @Override
                public void onAnimationEnd(SurfaceControl.Transaction tx,
                        CarDisplayAreaAnimationController
                                .CarDisplayAreaTransitionAnimator animator) {
                    mIsDisplayAreaAnimating = false;
                    mAnimationController.removeAnimator(animator.getToken());
                    if (mAnimationController.isAnimatorsConsumed()) {
                        WindowContainerTransaction wct = new WindowContainerTransaction();
                        updateForegroundDisplayBounds(wct, mForegroundApplicationDisplayBounds);
                        updateBackgroundDisplayBounds(wct);
                        updateBackgroundDAInsets(mCurrentState);
                    }
                }

                @Override
                public void onAnimationCancel(
                        CarDisplayAreaAnimationController
                                .CarDisplayAreaTransitionAnimator animator) {
                    mIsDisplayAreaAnimating = false;
                    mAnimationController.removeAnimator(animator.getToken());
                }
            };

    @Inject
    public CarDisplayAreaOrganizer(Executor executor, Context context, SyncTransactionQueue tx) {
        super(executor);
        mContext = context;
        mTransactionQueue = tx;
        mControlBarActivityName = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.config_controlBarActivity));
        mBackGroundActivities = new ArrayList<>();
        Resources resources = context.getResources();
        // Get bottom nav bar height.
        int navBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        mTotalScreenHeightWithoutNavBar = resources.getDimensionPixelSize(
                R.dimen.total_screen_height) - navBarHeight;
        mTotalScreenWidth = resources.getDimensionPixelSize(R.dimen.total_screen_width);
        String[] backgroundActivities = mContext.getResources().getStringArray(
                R.array.config_backgroundActivities);
        for (String backgroundActivity : backgroundActivities) {
            mBackGroundActivities
                    .add(ComponentName.unflattenFromString(backgroundActivity));
        }
        mTitleBarViewHeight = resources.getDimensionPixelSize(R.dimen.title_bar_height);
        mAnimationController = new CarDisplayAreaAnimationController(mContext);
        mHandlerForAnimation = mContext.getMainThreadHandler();

        Car.createCar(context, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                mCarServiceLifecycleListener);
    }

    int getDpiDensity() {
        if (mDpiDensity != -1) {
            return mDpiDensity;
        }

        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(DEFAULT_DISPLAY);
        Resources displayResources = mContext.createDisplayContext(display).getResources();
        mDpiDensity = displayResources.getConfiguration().densityDpi;

        return mDpiDensity;
    }

    private void updateBackgroundDAInsets(@PanelState int toState) {
        if (toState == DisplayAreaComponent.FULL) {
            logIfDebuggable("no inset change for full state");
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        WindowContainerToken backgroundDisplayToken =
                mBackgroundApplicationDisplay.getDisplayAreaInfo().token;
        Rect bounds = mBackgroundApplicationDisplayBounds;
        Rect applicationBounds = mForegroundApplicationDisplayBounds;

        // TODO(b/380947220)Workaround to show maps behind the status bar.
        Rect topInsets = new Rect(
                bounds.left,
                bounds.top,
                bounds.right,
                1);
        int bottomOverlay = applicationBounds.top + mTitleBarViewHeight;
        if (toState == CONTROL_BAR) {
            bottomOverlay = bounds.bottom;
        } else if (toState == DEFAULT || toState == FULL_TO_DEFAULT) {
            bottomOverlay = mForegroundApplicationDisplayBounds.top - mTitleBarViewHeight;
        }
        Rect bottomInsets = new Rect(
                bounds.left,
                bottomOverlay,
                bounds.right,
                bounds.bottom);
        logIfDebuggable(
                "Background bottom insets is " + bottomInsets + ", top insets is" + topInsets);

        updateDAInsets(backgroundDisplayToken, topInsets, bottomInsets);
        mTransactionQueue.queue(wct);
    }

    private void updateForegroundDAInsets(@PanelState int toState) {
        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationDisplay.getDisplayAreaInfo().token;

        // Workaround to show maps behind the status bar.
        Rect topInsets = new Rect();
        int bottomOverlay = mForegroundApplicationDisplayBounds.bottom - ((toState == FULL) ? 0
                : mControlBarHeight);
        Rect bottomInsets = new Rect(
                mForegroundApplicationDisplayBounds.left,
                bottomOverlay,
                mForegroundApplicationDisplayBounds.right,
                mForegroundApplicationDisplayBounds.bottom);
        logIfDebuggable("Foreground bottom insets is " + bottomInsets);

        updateDAInsets(foregroundDisplayToken, topInsets, bottomInsets);
    }

    void updateDAInsetsOnControlBarHeightChange(int controlbarHeight) {
        mControlBarHeight = controlbarHeight;
        updateBackgroundDAInsets(mCurrentState);
        updateForegroundDAInsets(mCurrentState);
    }

    void updateDAInsets(WindowContainerToken token, Rect topInsets, Rect bottomInsets) {
        int insetsFlags = 0;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.addInsetsSource(token, mInsetsOwner, 0,
                WindowInsets.Type.systemOverlays(),
                topInsets, new Rect[0], insetsFlags);
        wct.addInsetsSource(token, mInsetsOwner, 1,
                WindowInsets.Type.systemOverlays(),
                bottomInsets, new Rect[0], insetsFlags);
        mTransactionQueue.queue(wct);
    }

    boolean isDisplayAreaAnimating() {
        return mIsDisplayAreaAnimating;
    }

    // WCT will be queued in updateBackgroundDisplayBounds().
    protected void updateForegroundDisplayBounds(WindowContainerTransaction wct,
            Rect foregroundApplicationDisplayBound) {
        if (mForegroundApplicationDisplay == null) {
            Log.d(TAG, "mForegroundApplicationDisplay is null");
            return;
        }
        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationDisplay.getDisplayAreaInfo().token;

        int foregroundDisplayWidthDp =
                foregroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / getDpiDensity();
        int foregroundDisplayHeightDp =
                foregroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / getDpiDensity();
        wct.setBounds(foregroundDisplayToken, foregroundApplicationDisplayBound);
        wct.setScreenSizeDp(foregroundDisplayToken, foregroundDisplayWidthDp,
                foregroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(foregroundDisplayToken,
                Math.min(foregroundDisplayWidthDp, foregroundDisplayHeightDp));
    }

    private void updateBackgroundDisplayBounds(WindowContainerTransaction wct) {
        if (mBackgroundApplicationDisplay == null) {
            Log.d(TAG, "mBackgroundApplicationDisplay is null");
            return;
        }
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds;
        WindowContainerToken backgroundDisplayToken =
                mBackgroundApplicationDisplay.getDisplayAreaInfo().token;

        int backgroundDisplayWidthDp =
                backgroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / getDpiDensity();
        int backgroundDisplayHeightDp =
                backgroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / getDpiDensity();
        wct.setBounds(backgroundDisplayToken, backgroundApplicationDisplayBound);
        wct.setScreenSizeDp(backgroundDisplayToken, backgroundDisplayWidthDp,
                backgroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(backgroundDisplayToken,
                Math.min(backgroundDisplayWidthDp, backgroundDisplayHeightDp));
        mTransactionQueue.queue(wct);

        mTransactionQueue.runInSync(t -> {
            t.setWindowCrop(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.width(),
                    backgroundApplicationDisplayBound.height());
            t.setPosition(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.left,
                    backgroundApplicationDisplayBound.top);
        });
    }

    void resetWindowsOffset() {
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        mDisplayAreaTokenMap.forEach(
                (token, leash) -> {
                    CarDisplayAreaAnimationController.CarDisplayAreaTransitionAnimator animator =
                            mAnimationController.getAnimatorMap().remove(token);
                    if (animator != null && animator.isRunning()) {
                        animator.cancel();
                    }
                    tx.setPosition(leash, /* x= */ 0, /* y= */ 0)
                            .setWindowCrop(leash, /* width= */ -1, /* height= */ -1)
                            .setCornerRadius(leash, /* cornerRadius= */ -1);
                });
        tx.apply();
    }

    /**
     * Offsets the windows by a given offset on Y-axis, triggered also from screen rotation.
     * Directly perform manipulation/offset on the leash.
     */
    void scheduleOffset(int fromPos, int toPos,
            Rect finalBackgroundBounds, Rect finalForegroundBounds,
            Rect titleBarBounds,
            DisplayAreaAppearedInfo backgroundApplicationDisplay,
            DisplayAreaAppearedInfo foregroundDisplay,
            DisplayAreaAppearedInfo controlBarDisplay,
            DisplayAreaAppearedInfo voicePlateDisplay,
            @PanelState int toState,
            int durationMs,
            CarDisplayAreaAnimationCallback callback) {
        Log.d(TAG, "scheduleOffset");
        mCurrentState = toState;
        mBackgroundApplicationDisplay = backgroundApplicationDisplay;
        mForegroundApplicationDisplay = foregroundDisplay;
        mVoicePlateDisplay = voicePlateDisplay;
        mControlBarDisplay = controlBarDisplay;
        mTitleBarDisplayBounds.set(titleBarBounds);

        mDisplayAreaTokenMap.forEach(
                (token, leash) -> {
                    if (token == mBackgroundDisplayToken) {
                        mBackgroundApplicationDisplayBounds.set(finalBackgroundBounds);
                    } else if (token == mForegroundDisplayToken) {
                        mForegroundApplicationDisplayBounds.set(finalForegroundBounds);
                        animateWindows(token, leash, fromPos, toPos, durationMs, toState, callback);
                    }
                });

        if (mCurrentState == CONTROL_BAR) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            updateBackgroundDisplayBounds(wct);
        }
    }

    void animateWindows(WindowContainerToken token, SurfaceControl leash, float fromPos,
            float toPos, int durationMs, @PanelState int state,
            CarDisplayAreaAnimationCallback callback) {
        CarDisplayAreaAnimationController.CarDisplayAreaTransitionAnimator
                animator =
                mAnimationController.getAnimator(token, leash, fromPos, toPos);

        if (animator != null) {
            if (mDisplayAreaAnimationRunnable != null) {
                mDisplayAreaAnimationRunnable.stopAnimation();
                mHandlerForAnimation.removeCallbacks(mDisplayAreaAnimationRunnable);
            }
            mDisplayAreaAnimationRunnable = new DisplayAreaAnimationRunnable(animator, durationMs);
            if (state == CONTROL_BAR) {
                mDisplayAreaAnimationRunnable.addCallback(callback);
            }
            mHandlerForAnimation.post(mDisplayAreaAnimationRunnable);
        }
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        logIfDebuggable("onDisplayAreaAppeared" + displayAreaInfo);
        if (displayAreaInfo.featureId == BACKGROUND_TASK_CONTAINER) {
            mBackgroundDisplayToken = displayAreaInfo.token;
        } else if (displayAreaInfo.featureId == FOREGROUND_DISPLAY_AREA_ROOT) {
            mForegroundDisplayToken = displayAreaInfo.token;
        }
        mDisplayAreaTokenMap.put(displayAreaInfo.token, leash);
    }

    @Override
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
        logIfDebuggable("onDisplayAreaInfoChanged " + displayAreaInfo);

        // workaround to pass CTS CompatChangeTests. This is due to the fact that current design
        // have fixed size TDA and when the test tries to change the display dimens via
        // `adb shell wm size`. the TDA does not inherit from parent automatically as the bounds
        // were changed initially.
        // FEATURE_TASKDISPLAYAREA_PARENT will be called initially once and thereafter only when
        // `adb shell wm size` is executed. Right now the foreground DA takes the bounds
        // inherited from FEATURE_TASKDISPLAYAREA_PARENT which will be same as the new display
        // dimens as its attached to the root hierarchy.
        // TODO" remove the workaround when b/223809082 is completed.
        if (displayAreaInfo.featureId == FEATURE_TASKDISPLAYAREA_PARENT) {
            Configuration config = displayAreaInfo.configuration;
            Rect appBounds = config.windowConfiguration.getAppBounds();
            int height = appBounds.bottom;
            int width = appBounds.right;
            // check if height or width have changed
            if (mTotalScreenHeightWithoutNavBar == height && mTotalScreenWidth == width) {
                return;
            }

            WindowContainerTransaction wct = new WindowContainerTransaction();
            updateForegroundDisplayBounds(wct, appBounds);
            mTransactionQueue.queue(wct);

            // Move the forground DA to (0,0) as this will only be running in CTS.
            mTransactionQueue.runInSync(t -> {
                t.setPosition(mForegroundApplicationDisplay.getLeash(), 0, 0);
            });
        }
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        logIfDebuggable("onDisplayAreaVanished" + displayAreaInfo);

        if (!mIsRegistered) {
            mDisplayAreaTokenMap.remove(displayAreaInfo.token);
        }
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        List<DisplayAreaAppearedInfo> displayAreaInfos =
                super.registerOrganizer(displayAreaFeature);
        for (DisplayAreaAppearedInfo info : displayAreaInfos) {
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
        mIsRegistered = true;
        return displayAreaInfos;
    }

    private static void logIfDebuggable(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    @Override
    public void unregisterOrganizer() {
        super.unregisterOrganizer();
        mIsRegistered = false;
    }

    public void setForegroundApplicationsDisplay(
            DisplayAreaAppearedInfo foregroundApplicationsDisplay) {
        mForegroundApplicationDisplay = foregroundApplicationsDisplay;
    }

    public void setBackgroundApplicationDisplay(
            DisplayAreaAppearedInfo backgroundApplicationDisplay) {
        mBackgroundApplicationDisplay = backgroundApplicationDisplay;
    }

    public void setControlBarHeight(int controlBarDisplayHeight) {
        mControlBarHeight = controlBarDisplayHeight;
    }

    /**
     * A custom runnable with a flag to stop running the code within the {@link #run()} method when
     * the runnable is in the message queue. In such cases calling
     * {@link #removeCallbacksAndMessages(null)} won't work it only stops pending messages
     * (Runnables) not currently running runnable.
     */
    private class DisplayAreaAnimationRunnable implements Runnable {
        private final CarDisplayAreaAnimationController.CarDisplayAreaTransitionAnimator mAnimator;
        private final int mDurationMs;
        private boolean mStopAnimation = false;
        private List<CarDisplayAreaAnimationCallback> mCallbacks;

        DisplayAreaAnimationRunnable(
                CarDisplayAreaAnimationController.CarDisplayAreaTransitionAnimator animator,
                int durationMs) {
            mAnimator = animator;
            mDurationMs = durationMs;
            mCallbacks = new ArrayList<>();
        }

        @Override
        public void run() {
            if (mStopAnimation) {
                return;
            }

            for (CarDisplayAreaAnimationCallback callback : mCallbacks) {
                mAnimator.addDisplayAreaAnimationCallback(callback);
            }

            mAnimator.addDisplayAreaAnimationCallback(mDisplayAreaAnimationCallback)
                    .setDuration(mDurationMs)
                    .start();
        }

        public void stopAnimation() {
            // we don't call animator.cancel() here because if there is only one animation call
            // such as just to open the DA then it will get canceled here.
            mStopAnimation = true;
        }

        public void addCallback(CarDisplayAreaAnimationCallback callback) {
            mCallbacks.add(callback);
        }
    }
}
