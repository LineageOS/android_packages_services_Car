/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.support.car;

/**
 * Container class to hold static definitions for system api for Car.
 * Client should still use Car api for all operations, and this class is only for defining
 * additional parameters available when car api becomes system api.
 * @hide
 */
public class CarSystem {
    public static final String RADIO_SERVICE = "radio";
    public static final String HVAC_SERVICE = "hvac";

    /** Permission necessary to access Car HVAC APIs. */
    public static final String PERMISSION_CAR_HVAC =
            "android.support.car.permission.CAR_HVAC";

    /** Permission necesary to access Car RADIO system APIs. */
    public static final String PERMISSION_CAR_RADIO =
            "android.support.car.permission.CAR_RADIO";
}
