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
package com.android.car.os;

import static com.google.common.truth.Truth.assertThat;

import android.car.Car;
import android.car.os.CarPerformanceManager;
import android.car.os.ThreadPolicyWithPriority;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.car.MockedCarTestBase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarPerformanceManagerTest extends MockedCarTestBase {

    private CarPerformanceManager mCarPerformanceManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCarPerformanceManager = (CarPerformanceManager) getCar().getCarManager(
                Car.CAR_PERFORMANCE_SERVICE);
        assertThat(mCarPerformanceManager).isNotNull();
    }

    @Test
    public void testSetThreadPriority() throws Exception {
        ThreadPolicyWithPriority p = new ThreadPolicyWithPriority(
                ThreadPolicyWithPriority.SCHED_FIFO, /* priority= */ 1);
        mCarPerformanceManager.setThreadPriority(p);
        // TODO(b/156400843): getThreadPriority and verify the set operation succeeded.
    }
}
