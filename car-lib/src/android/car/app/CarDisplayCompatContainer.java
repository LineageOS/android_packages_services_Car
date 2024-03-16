/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.car.app;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UiThread;
import android.app.Activity;
import android.car.Car;
import android.car.builtin.util.Slogf;
import android.car.builtin.view.ViewHelper;
import android.car.feature.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A container that's used by the display compat host.
 *
 * @hide
 */
//TODO: b/329677726 Allow setting width, height and density from CarDisplayCompatContainer
@FlaggedApi(Flags.FLAG_DISPLAY_COMPATIBILITY)
@SystemApi
public final class CarDisplayCompatContainer {
    private static final String TAG = CarDisplayCompatContainer.class.getSimpleName();
    @NonNull
    private final CarActivityManager mCarActivityManager;
    @NonNull
    private Activity mActivity;
    @Nullable
    private Consumer<SurfaceView> mSurfaceViewCallback;
    @Nullable
    private CarTaskViewController mCarTaskViewController;
    @Nullable
    private RemoteCarTaskView mRemoteCarTaskView;
    @Nullable
    private Intent mIntent;

    @FlaggedApi(Flags.FLAG_DISPLAY_COMPATIBILITY)
    public static final class Builder {
        @NonNull
        private Activity mActivity;
        @Nullable
        private Consumer<SurfaceView> mSurfaceViewCallBack;
        @NonNull
        private CarActivityManager mCarActivityManager;

        public Builder(@NonNull Activity activity) {
            mActivity = activity;
        }

        /**
         * set density in dpi
         */
        @NonNull
        public Builder setSurfaceViewCallback(@Nullable Consumer<SurfaceView> callback) {
            mSurfaceViewCallBack = callback;
            return this;
        }

        /**
         * set a car instance
         */
        @NonNull
        Builder setCarActivityManager(@NonNull CarActivityManager carActivityManager) {
            mCarActivityManager = carActivityManager;
            return this;
        }

        /**
         * Returns a new instance of {@link CarDisplayCompatContainer}
         */
        @NonNull
        CarDisplayCompatContainer build() {
            return new CarDisplayCompatContainer(
                    mActivity, mSurfaceViewCallBack, mCarActivityManager);
        }
    }

    /**
     * @hide
     */
    CarDisplayCompatContainer(@NonNull Activity activity,
            @Nullable Consumer<SurfaceView> callback,
            @NonNull CarActivityManager carActivityManager) {
        mActivity = activity;
        mSurfaceViewCallback = callback;
        mCarActivityManager = carActivityManager;
    }

    /**
     * Set bounds of the display compat container
     *
     * @hide
     */
    @UiThread
    @SystemApi
    @RequiresPermission(Car.PERMISSION_MANAGE_DISPLAY_COMPATIBILITY)
    @NonNull
    public Rect setWindowBounds(@NonNull Rect windowBounds) {
        if (mRemoteCarTaskView != null) {
            mRemoteCarTaskView.setWindowBounds(windowBounds);
        }
        Rect actualWindowBounds = new Rect();
        ViewHelper.getBoundsOnScreen(mRemoteCarTaskView, actualWindowBounds);
        return actualWindowBounds;
    }

    /**
     * Set the visibility of the display compat container
     * see {@link android.view.View#setVisibility(int)}
     *
     * @hide
     */
    @UiThread
    @SystemApi
    @RequiresPermission(Car.PERMISSION_MANAGE_DISPLAY_COMPATIBILITY)
    public void setVisibility(int visibility) {
        if (mRemoteCarTaskView != null) {
            mRemoteCarTaskView.setVisibility(visibility);
        }
    }

    /**
     * Launch an activity on the display compat container
     *
     * @hide
     */
    @UiThread
    @SystemApi
    @RequiresPermission(allOf = {Car.PERMISSION_MANAGE_DISPLAY_COMPATIBILITY,
            Car.PERMISSION_MANAGE_CAR_SYSTEM_UI,
            INTERACT_ACROSS_USERS})
    public void startActivity(@NonNull Intent intent, @Nullable Bundle bundle) {
        mIntent = intent;
        Context context = mActivity.getApplicationContext();
        RemoteCarRootTaskViewCallback remoteCarTaskViewCallback =
                new RemoteCarRootTaskViewCallbackImpl(mRemoteCarTaskView,
                        intent, context, mSurfaceViewCallback);
        CarTaskViewControllerCallback carTaskViewControllerCallback =
                new CarTaskViewControllerCallbackImpl(remoteCarTaskViewCallback);

        mCarActivityManager.getCarTaskViewController(mActivity, mActivity.getMainExecutor(),
                carTaskViewControllerCallback);
    }

    /**
     * Called when the user clicks the back button
     *
     * @hide
     */
    @UiThread
    @SystemApi
    @RequiresPermission(Car.PERMISSION_MANAGE_DISPLAY_COMPATIBILITY)
    public void notifyBackPressed() {
    }

    private static final class RemoteCarRootTaskViewCallbackImpl implements
            RemoteCarRootTaskViewCallback {
        private RemoteCarTaskView mRemoteCarTaskView;
        private final Intent mIntent;
        private final Context mContext;
        private Consumer<SurfaceView> mSurfaceViewCallback;
        private RemoteCarRootTaskViewCallbackImpl(
                RemoteCarTaskView remoteCarTaskView,
                Intent intent,
                Context context,
                Consumer<SurfaceView> callback) {
            mRemoteCarTaskView = remoteCarTaskView;
            mIntent = intent;
            mContext = context;
            mSurfaceViewCallback = callback;
        }

        @Override
        public void onTaskViewCreated(@NonNull RemoteCarRootTaskView taskView) {
            mRemoteCarTaskView = taskView;
            if (mSurfaceViewCallback != null) {
                mSurfaceViewCallback.accept(mRemoteCarTaskView);
            }
            if (mIntent != null) {
                mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(mIntent);
            }
        }
    }

    private final class CarTaskViewControllerCallbackImpl implements CarTaskViewControllerCallback {
        private final RemoteCarRootTaskViewCallback mRemoteCarRootTaskViewCallback;

        private CarTaskViewControllerCallbackImpl(
                @Nullable RemoteCarRootTaskViewCallback remoteCarRootTaskViewCallback) {
            mRemoteCarRootTaskViewCallback = remoteCarRootTaskViewCallback;
        }

        @RequiresPermission("android.car.permission.CONTROL_CAR_APP_LAUNCH")
        @Override
        public void onConnected(@NonNull CarTaskViewController carTaskViewController) {
            mCarTaskViewController = carTaskViewController;
            if (mIntent != null) {
                String packageName = mIntent.getComponent().getPackageName();
                if (packageName != null) {
                    List<ComponentName> componentNames = queryActivitiesFromPackage(mActivity,
                            packageName);
                    mCarTaskViewController.createRemoteCarRootTaskView(
                            new RemoteCarRootTaskViewConfig.Builder()
                                    .setAllowListedActivities(componentNames)
                                    .build(),
                            mActivity.getMainExecutor(),
                            mRemoteCarRootTaskViewCallback);
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CarTaskViewController carTaskViewController) {
            mRemoteCarTaskView = null;
        }
    }

    private static List<ComponentName> queryActivitiesFromPackage(
            Activity activity, String packageName) {
        List<ComponentName> componentNames = new ArrayList<>();
        PackageInfo packageInfo;
        try {
            packageInfo = activity.getPackageManager().getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES);
            for (ActivityInfo info: packageInfo.activities) {
                ComponentName componentName = new ComponentName(packageName, info.name);
                componentNames.add(componentName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG, "Package '%s' not found : %s", packageName , e);
        }
        return componentNames;
    }
}
