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

package com.android.car.am;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.ComponentName;
import android.view.Display;
import android.window.DisplayAreaOrganizer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * This class contains security permission tests for {@link CarActivityManager}.
 */
@RunWith(AndroidJUnit4.class)
public class CarActivityManagerPermissionTest {

    private Car mCar;
    private CarActivityManager mCarActivityManager;

    @Before
    public void setUp() throws Exception {
        mCar = Car.createCar(InstrumentationRegistry.getInstrumentation().getTargetContext());
        mCarActivityManager = (CarActivityManager) mCar.getCarManager(Car.CAR_ACTIVITY_SERVICE);
    }

    @After
    public void tearDown() {
        mCar.disconnect();
    }

    @Test
    public void testSetPersistentActivity_requiresPermission() {
        ComponentName activity = new ComponentName("testPkg", "testActivity");
        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarActivityManager.setPersistentActivity(activity, Display.DEFAULT_DISPLAY,
                        DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER));

        assertThat(thrown.getMessage()).contains(Car.PERMISSION_CONTROL_CAR_APP_LAUNCH);
    }

    @Test
    public void testRegisterTaskMonitor_requiresPermission() {
        SecurityException thrown = assertThrows(SecurityException.class,
                () -> mCarActivityManager.registerTaskMonitor());

        assertThat(thrown.getMessage()).contains(android.Manifest.permission.MANAGE_ACTIVITY_TASKS);
    }
}
