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

package com.android.systemui.car.systembar;

import android.content.Context;
import android.util.AttributeSet;

import com.android.car.dockutil.Flags;
import com.android.systemui.R;
import com.android.systemui.car.hvac.CarUiPortraitTemperatureControlView;
import com.android.systemui.car.hvac.HvacView;

/**
 * A custom system bar for the automotive use case.
 * <p>
 * The system bar in the automotive use case is more like a list of shortcuts, rendered
 * in a linear layout.
 */
public class CarUiPortraitSystemBarView extends CarSystemBarView {
    public CarUiPortraitSystemBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    void setupHvacButton() {
        super.setupHvacButton();
        HvacView driverHvacView = findViewById(R.id.driver_hvac);
        HvacView passengerHvacView = findViewById(R.id.passenger_hvac);

        if (Flags.dockFeature()) {
            if (driverHvacView instanceof CarUiPortraitTemperatureControlView) {
                ((CarUiPortraitTemperatureControlView) driverHvacView)
                        .setTemperatureTextClickListener(this::onHvacClick);
            }
            if (passengerHvacView instanceof CarUiPortraitTemperatureControlView) {
                ((CarUiPortraitTemperatureControlView) passengerHvacView)
                        .setTemperatureTextClickListener(this::onHvacClick);
            }
        }
    }
}
