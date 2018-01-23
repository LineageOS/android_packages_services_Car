/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.car.drivingstate;

import android.car.drivingstate.CarUXRestrictionsEvent;
import android.car.drivingstate.ICarUXRestrictionsChangeListener;

/**
 * Binder interface for {@link android.car.drivingstate.CarUXRestrictionsManager}.
 * Check {@link android.car.drivingstate.CarUXRestrictionsManager} APIs for expected behavior of
 * each call.
 *
 * @hide
 */
interface ICarUXRestrictions {
    void registerUXRestrictionsChangeListener(in ICarUXRestrictionsChangeListener listener) = 0;
    void unregisterUXRestrictionsChangeListener(in ICarUXRestrictionsChangeListener listener) = 1;
    CarUXRestrictionsEvent getCurrentUXRestrictions() = 2;
}
