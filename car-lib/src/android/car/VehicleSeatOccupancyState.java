/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.car;

import android.car.annotation.AddedInOrBefore;

/**
 * Used by seat occupancy to enumerate the current occupancy state of the seat.
 * Use getProperty and setProperty in {@link android.car.hardware.property.CarPropertyManager} to
 * set and get this VHAL property.
 * @hide
 */
public final class VehicleSeatOccupancyState {
    @AddedInOrBefore(majorVersion = 33)
    public static final int UNKNOWN = 0;
    @AddedInOrBefore(majorVersion = 33)
    public static final int VACANT = 1;
    @AddedInOrBefore(majorVersion = 33)
    public static final int OCCUPIED = 2;

    private VehicleSeatOccupancyState() {}
}
