/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.hal;

import com.android.car.vehiclenetwork.VehiclePropValueUtil;

/**
 * Implementation of {@link HalServiceBase} that responsible for custom properties that were defined
 * by OEMs.
 */
/*package*/ class VendorExtensionHalService extends PropertyHalServiceBase {

    private final static String TAG = VendorExtensionHalService.class.getSimpleName();
    private final static boolean DEBUG = true;

    VendorExtensionHalService(VehicleHal vehicleHal) {
        super(vehicleHal, TAG, DEBUG);
    }

    @Override
    protected int managerToHalPropId(int managerPropId) {
        return VehiclePropValueUtil.isCustomProperty(managerPropId)
                ? managerPropId : NOT_SUPPORTED_PROPERTY;
    }

    @Override
    protected int halToManagerPropId(int halPropId) {
        return VehiclePropValueUtil.isCustomProperty(halPropId)
                ? halPropId : NOT_SUPPORTED_PROPERTY;
    }
}