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

package android.car.apitest;

import static com.google.common.truth.Truth.assertWithMessage;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@SmallTest
@RunWith(Parameterized.class)
public class TrailerStateTest {
    private final int mJavaConstantValue;
    private final int mHalConstantValue;

    public TrailerStateTest(int javaConstantValue, int halConstantValue) {
        mJavaConstantValue = javaConstantValue;
        mHalConstantValue = halConstantValue;
    }

    @Parameterized.Parameters
    public static Collection constantValues() {
        return Arrays.asList(
                new Object[][] {
                        {
                                android.car.hardware.property.TrailerState.STATE_UNKNOWN,
                                android.hardware.automotive.vehicle.TrailerState.UNKNOWN
                        },
                        {
                                android.car.hardware.property.TrailerState.STATE_NOT_PRESENT,
                                android.hardware.automotive.vehicle.TrailerState.NOT_PRESENT
                        },
                        {
                                android.car.hardware.property.TrailerState.STATE_PRESENT,
                                android.hardware.automotive.vehicle.TrailerState.PRESENT
                        },
                        {
                                android.car.hardware.property.TrailerState.STATE_ERROR,
                                android.hardware.automotive.vehicle.TrailerState.ERROR
                        }
                });
    }

    @Test
    public void testMatchWithVehicleHal() {
        assertWithMessage("Java constant")
                .that(mJavaConstantValue)
                .isEqualTo(mHalConstantValue);
    }
}
