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

package android.car.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Activity;
import android.car.annotation.ApiRequirements;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.builtin.util.Slogf;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class is used for creating task views & is created on a per activity basis.
 * @hide
 */
@SystemApi
public final class CarTaskViewController {
    private static final String TAG = CarTaskViewController.class.getSimpleName();
    static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);

    private final ICarSystemUIProxy mService;
    private final Activity mHostActivity;
    private final List<ControlledRemoteCarTaskView> mControlledRemoteCarTaskViews =
            new ArrayList<>();

    /**
     * @param service the binder interface to communicate with the car system UI.
     * @param hostActivity the activity that will be hosting the taskviews.
     * @hide
     */
    CarTaskViewController(@NonNull ICarSystemUIProxy service, @NonNull Activity hostActivity) {
        mService = service;
        mHostActivity = hostActivity;
    }

    /**
     * Creates a new {@link ControlledRemoteCarTaskView}.
     *
     * @param callbackExecutor the executor to get the {@link ControlledRemoteCarTaskViewCallback}
     *                         on.
     * @param controlledRemoteCarTaskViewCallbacks the callback to monitor the
     *                                             {@link ControlledRemoteCarTaskView} related
     *                                             events.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void createControlledRemoteCarTaskView(
            @NonNull Intent activityIntent,
            boolean autoRestartOnCrash,
            @NonNull Executor callbackExecutor,
            @NonNull ControlledRemoteCarTaskViewCallback controlledRemoteCarTaskViewCallbacks) {
        ControlledRemoteCarTaskView taskViewClient =
                new ControlledRemoteCarTaskView(
                        mHostActivity,
                        activityIntent,
                        autoRestartOnCrash,
                        callbackExecutor,
                        controlledRemoteCarTaskViewCallbacks,
                        /* carTaskViewController= */ this,
                        mHostActivity.getSystemService(UserManager.class));

        try {
            ICarTaskViewHost host = mService.createCarTaskView(taskViewClient.mICarTaskViewClient);
            taskViewClient.setRemoteHost(host);
            mControlledRemoteCarTaskViews.add(taskViewClient);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Unable to create task view.", e);
        }
    }

    /**
     * Releases all the resources held by the taskviews associated with this controller.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void release() {
        for (RemoteCarTaskView carTaskView : mControlledRemoteCarTaskViews) {
            carTaskView.release();
        }
        mControlledRemoteCarTaskViews.clear();
    }

    /**
     * Brings all the embedded tasks to the front.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void showEmbeddedTasks() {
        for (RemoteCarTaskView carTaskView : mControlledRemoteCarTaskViews) {
            // TODO(b/267314188): Add a new method in ICarSystemUI to call
            // showEmbeddedTask in a single WCT for multiple tasks.
            carTaskView.showEmbeddedTask();
        }
    }

    boolean isHostVisible() {
        return ActivityManagerHelper.isVisible(mHostActivity);
    }

    List<ControlledRemoteCarTaskView> getControlledRemoteCarTaskViews() {
        return mControlledRemoteCarTaskViews;
    }
}
