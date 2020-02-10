/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.car.VehiclePropertyIds.CURRENT_GEAR;
import static android.car.VehiclePropertyIds.INITIAL_USER_INFO;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class UserHalServiceTest {

    @Mock
    private VehicleHal mVehicleHal;

    private UserHalService mUserHalService;

    @Before
    public void setFixtures() {
        mUserHalService = new UserHalService(mVehicleHal);
    }

    @Test
    public void testTakeSupportedProperties_unsupportedOnly() {
        List<VehiclePropConfig> input = Arrays.asList(newConfig(CURRENT_GEAR));
        Collection<VehiclePropConfig> output = mUserHalService.takeSupportedProperties(input);
        assertThat(output).isNull();
    }

    @Test
    public void testTakeSupportedPropertiesAndInit() {
        VehiclePropConfig unsupportedConfig = newConfig(CURRENT_GEAR);
        VehiclePropConfig userInfoConfig = newSubscribableConfig(INITIAL_USER_INFO);
        List<VehiclePropConfig> input = Arrays.asList(unsupportedConfig, userInfoConfig);
        Collection<VehiclePropConfig> output = mUserHalService.takeSupportedProperties(input);
        assertThat(output).containsExactly(userInfoConfig);

        // Ideally there should be 2 test methods (one for takeSupportedProperties() and one for
        // init()), but on "real life" VehicleHal calls these 2 methods in sequence, and the latter
        // depends on the properties set by the former, so it's ok to test both here...
        mUserHalService.init();
        verify(mVehicleHal).subscribeProperty(mUserHalService, INITIAL_USER_INFO);
    }

    /**
     * Creates an empty config for the given property.
     */
    // TODO(b/149099817): move to common code
    private static VehiclePropConfig newConfig(int prop) {
        VehiclePropConfig config = new VehiclePropConfig();
        config.prop = prop;
        return config;
    }

    /**
     * Creates a config for the given property that passes the
     * {@link VehicleHal#isPropertySubscribable(VehiclePropConfig)} criteria.
     */
    // TODO(b/149099817): move to common code
    private static VehiclePropConfig newSubscribableConfig(int prop) {
        VehiclePropConfig config = newConfig(prop);
        config.access = VehiclePropertyAccess.READ_WRITE;
        config.changeMode = VehiclePropertyChangeMode.ON_CHANGE;
        return config;
    }
}
