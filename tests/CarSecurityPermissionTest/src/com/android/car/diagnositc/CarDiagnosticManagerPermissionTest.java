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
package com.android.car.diagnostic;

import static android.car.Car.DIAGNOSTIC_SERVICE;
import static android.car.diagnostic.CarDiagnosticManager.FRAME_TYPE_LIVE;

import static org.junit.Assert.assertThrows;

import android.app.UiAutomation;
import android.car.Car;
import android.car.diagnostic.CarDiagnosticEvent;
import android.car.diagnostic.CarDiagnosticManager;
import android.car.diagnostic.CarDiagnosticManager.OnDiagnosticEventListener;
import android.content.Context;
import android.os.Handler;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Objects;

/**
 * This class contains security permission tests for {@link CarDiagnosticManager}.
 */
@RunWith(AndroidJUnit4.class)
public class CarDiagnosticManagerPermissionTest {
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @Test
    public void testGetCarDiagnosticManager() {
        Car car = Objects.requireNonNull(Car.createCar(mContext, (Handler) null));
        assertThrows(SecurityException.class, () -> car.getCarManager(DIAGNOSTIC_SERVICE));
    }

    private CarDiagnosticManager getCarDiagnosticManager() {
        Car car = Objects.requireNonNull(Car.createCar(mContext, (Handler) null));
        return (CarDiagnosticManager) car.getCarManager(DIAGNOSTIC_SERVICE);
    }

    @Test
    public void testRegisterUnregisterListenerWithCorrectPermission() {
        mUiAutomation.adoptShellPermissionIdentity(Car.PERMISSION_CAR_DIAGNOSTIC_READ_ALL);

        CarDiagnosticManager manager = getCarDiagnosticManager();
        OnDiagnosticEventListener listener = (CarDiagnosticEvent carDiagnosticEvent) -> {};

        manager.registerListener(listener, FRAME_TYPE_LIVE, /* rate= */ 1);
        manager.unregisterListener(listener);

        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void testRegisterListenerWithWrongPermission() {
        mUiAutomation.adoptShellPermissionIdentity(Car.PERMISSION_CAR_DIAGNOSTIC_CLEAR);

        CarDiagnosticManager manager = getCarDiagnosticManager();
        OnDiagnosticEventListener listener = (CarDiagnosticEvent carDiagnosticEvent) -> {};

        assertThrows(SecurityException.class, () -> manager.registerListener(listener,
                FRAME_TYPE_LIVE, /* rate= */ 1));

        // unregisterListener does not require permission, so we skip the check here.

        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void testClearFreezeFrameWithCorrectPermission() {
        mUiAutomation.adoptShellPermissionIdentity(Car.PERMISSION_CAR_DIAGNOSTIC_CLEAR);
        CarDiagnosticManager manager = getCarDiagnosticManager();

        manager.clearFreezeFrames(/* timestamps= */ 0);

        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void testClearFreezeFrameWithWrongPermission() {
        mUiAutomation.adoptShellPermissionIdentity(Car.PERMISSION_CAR_DIAGNOSTIC_READ_ALL);
        CarDiagnosticManager manager = getCarDiagnosticManager();

        assertThrows(SecurityException.class, () -> manager.clearFreezeFrames(/* timestamps= */ 0));

        mUiAutomation.dropShellPermissionIdentity();
    }
}
