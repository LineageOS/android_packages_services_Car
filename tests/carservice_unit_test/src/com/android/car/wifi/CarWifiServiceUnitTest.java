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

package com.android.car.wifi;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.user.CarUserService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CarWifiServiceUnitTest {
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private CarUserService mCarUserService;
    @Mock
    private CarPowerManagementService mCarPowerManagementService;

    private CarWifiService mCarWifiService;

    @Before
    public void setUp() {
        when(mContext.getResources()).thenReturn(mResources);

        mCarWifiService = new CarWifiService(mContext, mCarPowerManagementService, mCarUserService);
    }

    @Test
    public void testCanControlPersistTetheringSettings_returnsTrue() {
        when(mResources.getBoolean(R.bool.config_enablePersistTetheringCapabilities)).thenReturn(
                true);
        mCarWifiService = new CarWifiService(mContext, mCarPowerManagementService, mCarUserService);

        boolean result = mCarWifiService.canControlPersistTetheringSettings();

        assertThat(result).isTrue();
    }
}
