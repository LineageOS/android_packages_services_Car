/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.car.rvc;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserHandle;
import android.util.Slog;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * View mediator for the rear view camera (RVC), which monitors EVS Service and also the gear
 * changes and shows the RVC when the gear position is R and otherwise hides it.
 */
@SysUISingleton
public class ExtendedRearViewCameraViewMediator extends RearViewCameraViewMediator {

    private static final String TAG = "ExtendedRearViewCameraView";
    private static final boolean DBG = false;

    private static final String ACTION_CONTROL_REAR_VIEW_CAMERA =
            "com.android.systemui.car.rvc.CONTROL_REAR_VIEW_CAMERA";
    private static final String EXTRA_CONTROL_REAR_VIEW_CAMERA = "action";
    private static final String SHOW_ACTION = "show";
    private static final String HIDE_ACTION = "hide";

    private final RearViewCameraViewController mRearViewCameraViewController;
    private final CarServiceProvider mCarServiceProvider;
    private final BroadcastDispatcher mBroadcastDispatcher;

    private CarPropertyManager mCarPropertyManager;

    // TODO(b/183674444): Remove the following when CarEvsService uses GEAR_SELECTION as fallback.
    private final CarPropertyEventCallback mPropertyEventCallback = new CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value) {
            if (DBG) Slog.d(TAG, "onChangeEvent value=" + value);
            if (value.getPropertyId() != VehiclePropertyIds.GEAR_SELECTION) {
                Slog.w(TAG, "Got the event for non-registered property: " + value.getPropertyId());
                return;
            }
            if ((Integer) value.getValue() == VehicleGear.GEAR_REVERSE) {
                mRearViewCameraViewController.start();
            } else {
                mRearViewCameraViewController.stop();
            }
        }
        @Override
        public void onErrorEvent(int propId, int zone) {
            Slog.e(TAG, "onErrorEvent propId=" + propId + ", zone=" + zone);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Slog.d(TAG, "onReceive: " + intent);
            if (ACTION_CONTROL_REAR_VIEW_CAMERA.equals(intent.getAction())) {
                String extra = intent.getStringExtra(EXTRA_CONTROL_REAR_VIEW_CAMERA);
                if (SHOW_ACTION.equals(extra)) {
                    mRearViewCameraViewController.start();
                } else if (HIDE_ACTION.equals(extra)) {
                    mRearViewCameraViewController.stop();
                } else {
                    Slog.d(TAG, "onReceive: invalid control action: " + extra);
                }
            }
        }
    };

    @Inject
    public ExtendedRearViewCameraViewMediator(
            RearViewCameraViewController rearViewCameraViewController,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher,
            @Main Executor mainExecutor) {
        super(rearViewCameraViewController, carServiceProvider, broadcastDispatcher, mainExecutor);
        mRearViewCameraViewController = rearViewCameraViewController;
        mCarServiceProvider = carServiceProvider;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void registerListeners() {
        super.registerListeners();

        mCarServiceProvider.addListener(car -> {
            mCarPropertyManager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (mCarPropertyManager == null) {
                Slog.e(TAG, "Unable to get CarPropertyManager");
                return;
            }
            if (DBG) Slog.d(TAG, "Registering mPropertyEventCallback.");
            mCarPropertyManager.registerCallback(mPropertyEventCallback,
                    VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_UI);
        });

        // Listen for test broadcast messages if we are in an emulator.
        if (Build.IS_EMULATOR) {
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver,
                    new IntentFilter(ACTION_CONTROL_REAR_VIEW_CAMERA), /* executor= */ null,
                    UserHandle.ALL);
        }
    }
}
