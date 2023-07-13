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

package com.android.car;

import static com.google.common.truth.Truth.assertWithMessage;

import android.car.Car;
import android.content.Context;
import android.os.Handler;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;

import java.util.Objects;

/**
 * This abstract class contains setup logic and utility methods for car security and permission
 * tests.
 */
public class AbstractCarManagerPermissionTest {

    protected Car mCar = null;
    protected final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    public final void connectCar() {
        mCar = Objects.requireNonNull(Car.createCar(mContext, (Handler) null));
        assertWithMessage("mCar").that(mCar).isNotNull();
    }

    @After
    public final void disconnectCar() {
        mCar.disconnect();
    }
}
