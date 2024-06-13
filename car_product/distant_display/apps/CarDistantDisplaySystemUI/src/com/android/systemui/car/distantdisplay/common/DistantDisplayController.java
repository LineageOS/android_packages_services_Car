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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.qc.QCItem;
import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.util.AppCategoryDetector;
import com.android.systemui.car.qc.DistantDisplayControlsUpdateListener;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.settings.UserTracker;

import com.google.android.car.distantdisplay.service.DistantDisplayService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

/**
 * A Controller that observes to foreground tasks and media sessions to provide controls for distant
 * display quick controls panel.
 **/
@SysUISingleton
public class DistantDisplayController {
    public static final String TAG = DistantDisplayController.class.getSimpleName();

    private final int mDistantDisplayId;
    private final PackageManager mPackageManager;
    private final MediaSessionManager mMediaSessionManager;
    private final Context mContext;
    private final UserTracker mUserTracker;
    private final TaskViewController mTaskViewController;
    private final List<ComponentName> mRestrictedActivities;
    private final Drawable mDistantDisplayDrawable;
    private final Drawable mDefaultDisplayDrawable;
    @GuardedBy("mStatusChangeListeners")
    private final Set<StatusChangeListener> mStatusChangeListeners = new ArraySet<>();
    @Nullable
    private ComponentName mTopActivityOnDefaultDisplay;
    @Nullable
    private ComponentName mTopActivityOnDistantDisplay;
    private DistantDisplayControlsUpdateListener mDistantDisplayControlsUpdateListener;

    /**
     * Interface for listeners to register for status changes based on Media Session and TaskStack
     * Changes.
     **/
    public interface StatusChangeListener {
        /**
         * Callback triggered for display changes of the task.
         */
        default void onDisplayChanged(int displayId) {}

        /**
         * Callback triggered for visibility changes.
         */
        default void onVisibilityChanged(boolean visible) {}
    }

    private final TaskViewController.Callback mTaskViewControllerCallback =
            new TaskViewController.Callback() {
                @Override
                public void topAppOnDisplayChanged(int displayId, ComponentName componentName) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        mTopActivityOnDefaultDisplay = componentName;
                        updateButtonState();

                    } else if (displayId == mDistantDisplayId) {
                        mTopActivityOnDistantDisplay = componentName;
                        updateButtonState();
                    }
                }
            };

    private final OnActiveSessionsChangedListener mOnActiveSessionsChangedListener =
            controllers -> {
                updateButtonState();
            };

    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            mMediaSessionManager.addOnActiveSessionsChangedListener(null,
                    mUserTracker.getUserHandle(),
                    mContext.getMainExecutor(), mOnActiveSessionsChangedListener);
        }

        @Override
        public void onBeforeUserSwitching(int newUser) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(
                    mOnActiveSessionsChangedListener);
        }
    };

    @Inject
    public DistantDisplayController(Context context, UserTracker userTracker,
            TaskViewController taskViewController) {
        mContext = context;
        mUserTracker = userTracker;
        mTaskViewController = taskViewController;
        mPackageManager = context.getPackageManager();
        mDistantDisplayId = mContext.getResources().getInteger(R.integer.config_distantDisplayId);
        mDistantDisplayDrawable = mContext.getResources().getDrawable(
                R.drawable.ic_distant_display_nav, /* theme= */ null);
        mDefaultDisplayDrawable = mContext.getResources().getDrawable(
                R.drawable.ic_default_display_nav, /* theme= */ null);
        mRestrictedActivities = new ArrayList<>();
        String[] ddRestrictedActivities = mContext.getResources().getStringArray(
                R.array.config_restrictedActivities);
        for (String ddRestrictedActivity : ddRestrictedActivities) {
            mRestrictedActivities.add(
                    ComponentName.unflattenFromString(ddRestrictedActivity));
        }
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mMediaSessionManager.addOnActiveSessionsChangedListener(/* notificationListener= */ null,
                userTracker.getUserHandle(),
                mContext.getMainExecutor(), mOnActiveSessionsChangedListener);
        mUserTracker.addCallback(mUserChangedCallback, context.getMainExecutor());
        mTaskViewController.addCallback(mTaskViewControllerCallback);
    }

    /**
     * Adds a listener that needs to be notified of controls change available in status panel.
     **/
    public void addDistantDisplayControlStatusInfoListener(StatusChangeListener listener) {
        synchronized (mStatusChangeListeners) {
            mStatusChangeListeners.add(listener);
            notifyStatusChangeListener(listener);
        }
    }

    /**
     * Removes a DistantDisplayControlStatusInfoListener.
     **/
    public void removeDistantDisplayControlStatusInfoListener(StatusChangeListener listener) {
        synchronized (mStatusChangeListeners) {
            mStatusChangeListeners.remove(listener);
        }
    }

    public void setDistantDisplayControlsUpdateListener(
            DistantDisplayControlsUpdateListener distantDisplayControlsUpdateListener) {
        mDistantDisplayControlsUpdateListener = distantDisplayControlsUpdateListener;
    }

    /**
     * @return A metadata of current app on distant/default display
     * {@link com.android.systemui.car.distantdisplay.common.DistantDisplayQcItem}
     */
    public DistantDisplayQcItem getMetadata() {
        Optional<MediaController> optionalMediaController =
                getMediaControllerFromActiveMediaSession();
        if (optionalMediaController.isEmpty()) return null;

        MediaController mediaController = optionalMediaController.get();
        String packageName = mediaController.getPackageName();
        Drawable mediaAppIcon = null;
        String mediaAppName = "";
        try {
            ApplicationInfo applicationInfo = mPackageManager.getApplicationInfoAsUser(packageName,
                    PackageManager.GET_META_DATA, mUserTracker.getUserId());
            mediaAppName = (String) applicationInfo.loadLabel(mPackageManager);
            mediaAppIcon = applicationInfo.loadIcon(mPackageManager);
        } catch (PackageManager.NameNotFoundException e) {
            logIfDebuggable("Error retrieving application info");
        }
        MediaDescription mediaDescription = mediaController.getMetadata().getDescription();
        String mediaTitle = (String) mediaDescription.getTitle();
        return new DistantDisplayQcItem.Builder()
                .setTitle(mediaTitle)
                .setSubtitle(mediaAppName)
                .setIcon(mediaAppIcon)
                .build();
    }

    /**
     * @return Controls to move content between display
     * {@link com.android.systemui.car.distantdisplay.common.DistantDisplayQcItem}
     */
    public List<DistantDisplayQcItem> getControls() {
        List<DistantDisplayQcItem> items = new ArrayList<>();
        if (canMoveBetweenDisplays(mTopActivityOnDistantDisplay)) {
            DistantDisplayQcItem item1 = new DistantDisplayQcItem.Builder()
                    .setTitle(mContext.getString(R.string.qc_bring_back_to_default_display_title))
                    .setIcon(mDefaultDisplayDrawable)
                    .setActionHandler(createActionHandler(DistantDisplayService.State.DEFAULT))
                    .build();
            items.add(item1);
            return items;
        } else if (canMoveBetweenDisplays(mTopActivityOnDefaultDisplay)) {
            DistantDisplayQcItem item1 = new DistantDisplayQcItem.Builder()
                    .setTitle(mContext.getString(R.string.qc_send_to_center_title))
                    .setIcon(mDistantDisplayDrawable)
                    .setActionHandler(createActionHandler(DistantDisplayService.State.DRIVER_DD))
                    .build();
            DistantDisplayQcItem item2 = new DistantDisplayQcItem.Builder()
                    .setTitle(mContext.getString(R.string.qc_send_to_right_title))
                    .setIcon(mDistantDisplayDrawable)
                    .setActionHandler(createActionHandler(DistantDisplayService.State.PASSENGER_DD))
                    .build();
            items.add(item1);
            items.add(item2);
            return items;
        } else {
            return null;
        }
    }

    private QCItem.ActionHandler createActionHandler(DistantDisplayService.State state) {
        return new QCItem.ActionHandler() {
            @Override
            public void onAction(@NonNull QCItem item, @NonNull Context context,
                    @NonNull Intent intent) {
                switch (state) {
                    case DEFAULT -> mTaskViewController.moveTaskFromDistantDisplay();
                    case DRIVER_DD -> mTaskViewController.moveTaskToDistantDisplay();
                    case PASSENGER_DD -> mTaskViewController.moveTaskToRightDistantDisplay();
                }
            }

            @Override
            public boolean isActivity() {
                return true;
            }
        };
    }

    private Optional<MediaController> getMediaControllerFromActiveMediaSession() {

        String foregroundMediaPackage;
        if (isVideoApp(mTopActivityOnDistantDisplay)) {
            foregroundMediaPackage = mTopActivityOnDistantDisplay.getPackageName();
        } else if (isVideoApp(mTopActivityOnDefaultDisplay)) {
            foregroundMediaPackage = mTopActivityOnDefaultDisplay.getPackageName();
        } else {
            foregroundMediaPackage = null;
        }

        if (foregroundMediaPackage == null) return Optional.empty();

        return mMediaSessionManager.getActiveSessionsForUser(/* notificationListener= */ null,
                        mUserTracker.getUserHandle())
                .stream()
                .filter(
                        mediaController -> foregroundMediaPackage
                                .equals(mediaController.getPackageName()))
                .findFirst();
    }

    private boolean canMoveBetweenDisplays(ComponentName componentName) {
        return componentName != null && !mRestrictedActivities.contains(componentName);
    }

    private boolean isVideoApp(ComponentName componentName) {
        if (componentName == null) return false;
        String packageName = componentName.getPackageName();
        if (packageName == null) return false;
        return AppCategoryDetector.isVideoApp(mUserTracker.getUserContext().getPackageManager(),
                packageName);
    }

    private void updateButtonState() {
        synchronized (mStatusChangeListeners) {
            mStatusChangeListeners.forEach(this::notifyStatusChangeListener);
        }

        if (mDistantDisplayControlsUpdateListener != null) {
            mDistantDisplayControlsUpdateListener.onControlsChanged();
        }
    }

    private void notifyStatusChangeListener(StatusChangeListener listener) {
        if (listener == null) return;

        if (canMoveBetweenDisplays(mTopActivityOnDistantDisplay)) {
            listener.onDisplayChanged(mDistantDisplayId);
            listener.onVisibilityChanged(true);
        } else if (canMoveBetweenDisplays(mTopActivityOnDefaultDisplay)) {
            listener.onDisplayChanged(Display.DEFAULT_DISPLAY);
            listener.onVisibilityChanged(true);
        } else {
            listener.onVisibilityChanged(false);
        }
    }

    private static void logIfDebuggable(String message) {
        Log.d(TAG, message);
    }
}
