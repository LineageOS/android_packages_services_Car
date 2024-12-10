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

package com.android.car.systeminterface.test;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.VisibleForTesting;

/**
 * An empty implementation used for testing.
 */
@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
@VisibleForTesting
public class DisplayInterfaceEmptyImpl implements DisplayInterface {
    @Override
    public void init(CarPowerManagementService carPowerManagementService,
            CarUserService carUserService) {}

    @Override
    public void onDisplayBrightnessChangeFromVhal(int displayId, int brightness) {}

    @Override
    public void setDisplayState(int displayId, boolean on) {}

    @Override
    public void setAllDisplayState(boolean on) {}

    @Override
    public void startDisplayStateMonitoring() {}

    @Override
    public void stopDisplayStateMonitoring() {}

    @Override
    public void refreshDefaultDisplayBrightness() {}

    @Override
    public void refreshDisplayBrightness(int displayid) {}

    @Override
    public boolean isAnyDisplayEnabled() {
        return false;
    }

    @Override
    public boolean isDisplayEnabled(int displayId) {
        return false;
    }
}
