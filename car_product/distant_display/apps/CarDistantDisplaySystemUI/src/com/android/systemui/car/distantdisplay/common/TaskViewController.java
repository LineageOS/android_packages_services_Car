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

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver;
import com.android.systemui.car.distantdisplay.activity.NavigationTaskViewWallpaperActivity;
import com.android.systemui.car.distantdisplay.activity.RootTaskViewWallpaperActivity;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.HashSet;
import java.util.List;
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
    private static final String ROOT_SURFACE = "RootViewSurface";
    private static final String NAVIGATION_SURFACE = "NavigationViewSurface";

    private static ArrayMap<SurfaceHolder, VirtualDisplay> sVirtualDisplays = new ArrayMap<>();

    private Context mContext;
    private DisplayManager mDisplayManager;
    private LayoutInflater mInflater;
    private Intent mBaseIntentForTopTask;
    private Intent mBaseIntentForTopTaskDD;
    private MediaSessionManager mMediaSessionManager;
    private SurfaceHolder mNavigationSurfaceHolder;
    private SurfaceHolder mRootSurfaceHolder;
    private SurfaceHolder.Callback mNavigationViewCallback;
    private SurfaceHolder.Callback mRootViewCallback;
    private Set<ComponentName> mNavigationActivities;

    private final TaskStackChangeListener mTaskStackChangeLister = new TaskStackChangeListener() {

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            if (DEBUG) {
                Log.d(TAG, "onTaskMovedToFront: displayId: " + taskInfo.displayId + ", " + taskInfo
                        + " token: " + taskInfo.token);
            }
            if (taskInfo.displayId == DEFAULT_DISPLAY_ID) {
                mBaseIntentForTopTask = taskInfo.baseIntent;
            } else if (taskInfo.displayId == getVirtualDisplayId(mRootSurfaceHolder)) {
                mBaseIntentForTopTaskDD = taskInfo.baseIntent;
            }
        }
    };

    public TaskViewController(
            Context context, DisplayManager displayManager, LayoutInflater inflater) {
        mContext = context;
        mDisplayManager = displayManager;
        mInflater = inflater;
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mNavigationViewCallback = new SurfaceHolderCallback(this, NAVIGATION_SURFACE, mContext);
        mRootViewCallback = new SurfaceHolderCallback(this, ROOT_SURFACE, mContext);
    }

    private static ArraySet<ComponentName> convertToComponentNames(String[] componentStrings) {
        ArraySet<ComponentName> componentNames = new ArraySet<>(componentStrings.length);
        for (int i = componentStrings.length - 1; i >= 0; i--) {
            componentNames.add(ComponentName.unflattenFromString(componentStrings[i]));
        }
        return componentNames;
    }

    /**
     * Initializes and setups the TaskViews. This method accepts a container with the base layout
     * then attaches the surface view to it. There are 2 surface views created.
     */
    public void initialize(ViewGroup parent) {
        FrameLayout navigationViewLayout = parent.findViewById(R.id.navigationView);
        FrameLayout rootVeiwLayout = parent.findViewById(R.id.rootView);

        View navigationViewContainer = mInflater
                .inflate(R.layout.car_distant_display_navigation_surfaceview, null);
        ConstraintLayout navigationView = navigationViewContainer.findViewById(R.id.container);
        SurfaceView view = navigationViewContainer.findViewById(R.id.content);
        view.getHolder().addCallback(mNavigationViewCallback);
        navigationViewLayout.addView(navigationView);
        mNavigationSurfaceHolder = view.getHolder();

        View rootViewContainer = mInflater
                .inflate(R.layout.car_distant_display_root_surfaceview, null);
        ConstraintLayout rootView = rootViewContainer.findViewById(R.id.container);
        SurfaceView view1 = rootViewContainer.findViewById(R.id.content);
        view1.getHolder().addCallback(mRootViewCallback);
        rootVeiwLayout.addView(rootView);
        mRootSurfaceHolder = view1.getHolder();

        mNavigationActivities = new HashSet<>(convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_navigationActivities)));

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackChangeLister);

        if (DEBUG) {
            setupDebuggingThroughAdb();
        }
    }

    private void setupDebuggingThroughAdb() {
        IntentFilter filter = new IntentFilter(MoveTaskReceiver.MOVE_ACTION);
        MoveTaskReceiver receiver = new MoveTaskReceiver();
        receiver.registerOnChangeDisplayForTask(this::changeDisplayForTask);
        //mContext.registerReceiver(receiver, filter);
        ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void changeDisplayForTask(String movement) {
        Log.i(TAG, "Handling movement command : " + movement);
        if (movement.equals(MoveTaskReceiver.MOVE_FROM_DISTANT_DISPLAY)) {
            startActivityOnDisplay(mBaseIntentForTopTaskDD, DEFAULT_DISPLAY_ID);
        } else if (movement.equals(MoveTaskReceiver.MOVE_TO_DISTANT_DISPLAY)) {
            if (taskHasActiveMediaSession(mBaseIntentForTopTask.getComponent().getPackageName())) {
                startActivityOnDisplay(
                        mBaseIntentForTopTask, getVirtualDisplayId(mRootSurfaceHolder));
            }
        }
    }

    private boolean taskHasActiveMediaSession(@Nullable String packageName) {
        if (packageName == null) {
            Log.w(TAG, "package name is null");
            return false;
        }
        List<MediaController> controllerList =
                mMediaSessionManager.getActiveSessions(/* notificationListener= */ null);
        for (MediaController ctrl : controllerList) {
            if (packageName.equals(ctrl.getPackageName())) {
                return true;
            }
            Log.i(TAG, "[" + packageName + "]" + " active media session: " + ctrl.getPackageName());
        }
        logIfDebuggable("package: " + packageName
                + " does not have an active MediaSession, cannot move the Task");
        return false;
    }

    private void startActivityOnDisplay(Intent intent, int displayId) {
        if (intent == null) {
            return;
        }
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, /* enterResId=*/
                0, /* exitResId= */ 0);
        options.setLaunchDisplayId(displayId);
        logIfDebuggable("starting task" + intent + "on display " + displayId);
        mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }

    private int getVirtualDisplayId(SurfaceHolder holder) {
        VirtualDisplay display = getVirtualDisplay(holder);
        if (display == null) {
            Log.e(TAG, "Could not find virtual display with given holder");
            return Display.INVALID_DISPLAY;
        }
        return display.getDisplay().getDisplayId();
    }

    /**
     * Provides a DisplayManager.
     * This method will be called from the SurfaceHolderCallback to create a virtual display.
     */
    public DisplayManager getDisplayManager() {
        return mDisplayManager;
    }

    private void launchActivity(int displayId, Intent intent) {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        options.setLaunchDisplayId(displayId);
        mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }

    /**
     * Launches the wallpaper activity on a virtuall display which belongs to a surface.
     * This method will be called when a virtual display is created from the SrufaceHolderCallback.
     */
    public void launchWallpaper(String surface) {
        if (surface.equals(ROOT_SURFACE)) {
            launchActivity(getVirtualDisplayId(mRootSurfaceHolder),
                    RootTaskViewWallpaperActivity.createIntent(mContext));
        } else if (surface.equals(NAVIGATION_SURFACE)) {
            launchActivity(getVirtualDisplayId(mNavigationSurfaceHolder),
                    NavigationTaskViewWallpaperActivity.createIntent(mContext));
        } else {
            Log.e(TAG, "Invalid surfacename : " + surface);
        }
    }

    /**
     * Gets a SurfaceHolder object and returns a VirtualDisplay it belongs to.
     */
    public VirtualDisplay getVirtualDisplay(SurfaceHolder holder) {
        return sVirtualDisplays.get(holder);
    }

    /**
     * Builds a DB with SurfaceHolder and mapped VirtualDisplay.
     */
    public void putVirtualDisplay(SurfaceHolder holder, VirtualDisplay display) {
        if (sVirtualDisplays.get(holder) == null) {
            sVirtualDisplays.put(holder, display);
        } else {
            Log.w(TAG, "surface holder with VD already exists.");
        }
    }

    private static void logIfDebuggable(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    public static class SurfaceHolderCallback implements SurfaceHolder.Callback {

        public static final String TAG = SurfaceHolderCallback.class.getSimpleName();
        ;
        private final String mSurfaceName;
        private final TaskViewController mController;
        private final Context mContext;

        public SurfaceHolderCallback(TaskViewController controller, String name, Context context) {
            Log.i(TAG, "Create SurfaceHolderCallback for " + name);
            mSurfaceName = name;
            mController = controller;
            mContext = context;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "surfaceCreated, holder: " + holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "surfaceChanged, holder: " + holder + ", size:" + width + "x" + height
                    + ", format:" + format);

            VirtualDisplay virtualDisplay = mController.getVirtualDisplay(holder);
            if (virtualDisplay == null) {
                virtualDisplay = createVirtualDisplay(holder.getSurface(), width, height);
                mController.putVirtualDisplay(holder, virtualDisplay);
                int displayId = virtualDisplay.getDisplay().getDisplayId();
                Log.i(TAG, "Created a virtual display for " + mSurfaceName + ". ID: " + displayId);
                mController.launchWallpaper(mSurfaceName);
            } else {
                Log.i(TAG, "SetSurface display_id: " + virtualDisplay.getDisplay().getDisplayId()
                        + ", surface name:" + mSurfaceName);
                virtualDisplay.setSurface(holder.getSurface());
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "surfaceDestroyed, holder: " + holder + ", detaching surface from"
                    + " display, surface: " + holder.getSurface());
            VirtualDisplay virtualDisplay = mController.getVirtualDisplay(holder);
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(null);
            }
        }

        private VirtualDisplay createVirtualDisplay(Surface surface, int width, int height) {
            DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            Log.i(TAG, "createVirtualDisplay, surface: " + surface + ", width: " + width
                    + "x" + height + " density: " + metrics.densityDpi);
            return mController.getDisplayManager().createVirtualDisplay(
                    /* projection= */ null, "DistantDisplay-" + mSurfaceName + "-VD",
                    width, height, metrics.densityDpi, surface,
                    VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    /* callback= */
                    null, /* handler= */ null, "DistantDisplay-" + mSurfaceName);
        }
    }

}
