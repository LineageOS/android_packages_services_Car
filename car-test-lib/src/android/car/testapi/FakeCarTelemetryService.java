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

package android.car.testapi;

import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;

/**
 * A fake implementation of {@link ICarTelemetryService.Stub} to facilitate the use of
 * {@link android.car.telemetry.CarTelemetryManager} in external unit tests.
 *
 * @hide
 */
public class FakeCarTelemetryService extends ICarTelemetryService.Stub implements
        CarTelemetryController {

    private ICarTelemetryServiceListener mListener;

    @Override
    public void setListener(ICarTelemetryServiceListener listener) {
        mListener = listener;
    }

    @Override
    public void clearListener() {
        mListener = null;
    }

    /**************************** CarTelemetryController impl ********************************/
    @Override
    public boolean isListenerSet() {
        return mListener != null;
    }
}
