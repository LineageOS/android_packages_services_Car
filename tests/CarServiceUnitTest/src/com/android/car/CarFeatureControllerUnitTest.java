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

package com.android.car;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.VehicleHal;
import com.android.car.test.utils.TemporaryDirectory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

@SmallTest
public final class CarFeatureControllerUnitTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarFeatureControllerUnitTest.class.getSimpleName();
    private static final String CAR_NAVIGATION_SERVICE_FEATURE = "car_navigation_service";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final HalPropValueBuilder mHalPropValueBuilder = new HalPropValueBuilder(
            /* isAidl= */ true);
    @Mock
    private VehicleHal mMockHal;
    private TemporaryDirectory mTestDir;

    @Before
    public void setUp() throws Exception {
        mTestDir = new TemporaryDirectory(TAG);
    }

    @After
    public void tearDown() throws Exception {
        if (mTestDir != null) {
            try {
                mTestDir.close();
            } catch (Exception e) {
                Log.w(TAG, "could not remove temporary directory", e);
            }
        }
    }

    @Test
    public void testIsFeatureEnabled_enabled() {
        HalPropValue disabledFeaturesResponse = mHalPropValueBuilder.build(
                VehicleProperty.DISABLED_OPTIONAL_FEATURES, /* areaId= */ 0, /* value= */ "");
        when(mMockHal.getIfSupportedOrFailForEarlyStage(
                eq(VehicleProperty.DISABLED_OPTIONAL_FEATURES), anyInt())).thenReturn(
                        disabledFeaturesResponse);
        CarFeatureController service =
                new CarFeatureController(mContext, mTestDir.getDirectory(), mMockHal);

        boolean isNavigationEnabled = service.isFeatureEnabled(CAR_NAVIGATION_SERVICE_FEATURE);

        assertWithMessage("Navigation feature enabled status").that(isNavigationEnabled).isTrue();
    }

    @Test
    public void testIsFeatureEnabled_disabled() {
        HalPropValue disabledFeaturesResponse = mHalPropValueBuilder.build(
                VehicleProperty.DISABLED_OPTIONAL_FEATURES, /* areaId= */ 0,
                /* value= */ CAR_NAVIGATION_SERVICE_FEATURE);
        when(mMockHal.getIfSupportedOrFailForEarlyStage(
                eq(VehicleProperty.DISABLED_OPTIONAL_FEATURES), anyInt())).thenReturn(
                disabledFeaturesResponse);
        CarFeatureController service =
                new CarFeatureController(mContext, mTestDir.getDirectory(), mMockHal);

        boolean isNavigationEnabled = service.isFeatureEnabled(CAR_NAVIGATION_SERVICE_FEATURE);

        assertWithMessage("Navigation feature enabled status").that(isNavigationEnabled).isFalse();
    }
}
