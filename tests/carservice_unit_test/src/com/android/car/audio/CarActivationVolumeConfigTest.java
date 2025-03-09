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

package com.android.car.audio;

import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;

import static org.junit.Assert.assertThrows;

import android.car.test.AbstractExpectableTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class CarActivationVolumeConfigTest extends AbstractExpectableTestCase {

    private static final int MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE = 10;
    private static final int MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE = 90;
    private static final int ACTIVATION_VOLUME_INVOCATION_TYPE =
            CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;

    private static final CarActivationVolumeConfig CAR_ACTIVATION_VOLUME_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_INVOCATION_TYPE,
                    MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE, MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE);

    @Test
    public void construct_withMinActivationVolumePercentageHigherThanMax() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new CarActivationVolumeConfig(ACTIVATION_VOLUME_INVOCATION_TYPE,
                        MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE,
                        MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE));

        expectWithMessage("Exception for min activation volume percentage higher than max")
                .that(thrown).hasMessageThat().contains("can not be higher than");
    }

    @Test
    public void getInvocationType() {
        expectWithMessage("Activation volume invocation type")
                .that(CAR_ACTIVATION_VOLUME_CONFIG.getInvocationType())
                .isEqualTo(ACTIVATION_VOLUME_INVOCATION_TYPE);
    }

    @Test
    public void getMinActivationVolumePercentage() {
        expectWithMessage("Min activation volume percentage")
                .that(CAR_ACTIVATION_VOLUME_CONFIG.getMinActivationVolumePercentage())
                .isEqualTo(MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE);
    }

    @Test
    public void getMaxActivationVolumePercentage() {
        expectWithMessage("Max activation volume percentage")
                .that(CAR_ACTIVATION_VOLUME_CONFIG.getMaxActivationVolumePercentage())
                .isEqualTo(MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE);
    }

    @Test
    public void equals_withSameObject() {
        CarActivationVolumeConfig config =
                new CarActivationVolumeConfig(CAR_ACTIVATION_VOLUME_CONFIG.getInvocationType(),
                        CAR_ACTIVATION_VOLUME_CONFIG.getMinActivationVolumePercentage(),
                        CAR_ACTIVATION_VOLUME_CONFIG.getMaxActivationVolumePercentage());

        expectWithMessage("Car volume activation")
                .that(config.equals(CAR_ACTIVATION_VOLUME_CONFIG)).isTrue();
    }

    @Test
    public void equals_withNullObject() {
        expectWithMessage("Car volume activation with null object")
                .that(CAR_ACTIVATION_VOLUME_CONFIG.equals(null)).isFalse();
    }

    @Test
    public void equals_withDifferentObject() {
        expectWithMessage("Car volume activation with different object")
                .that(CAR_ACTIVATION_VOLUME_CONFIG.equals(new Object())).isFalse();
    }

    @Test
    public void equals_withDifferentConfiguration() {
        CarActivationVolumeConfig testActivationConfig =
                new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_SOURCE_CHANGED,
                        MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE, MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE);

        expectWithMessage("Car volume activation different configuration")
                .that(testActivationConfig.equals(CAR_ACTIVATION_VOLUME_CONFIG)).isFalse();
    }

    @Test
    public void hashCode_withSameConfiguration() {
        CarActivationVolumeConfig testActivationConfig =
                new CarActivationVolumeConfig(ACTIVATION_VOLUME_INVOCATION_TYPE,
                        MIN_ACTIVATION_GAIN_INDEX_PERCENTAGE, MAX_ACTIVATION_GAIN_INDEX_PERCENTAGE);

        expectWithMessage("Hash result for same configuration")
                .that(testActivationConfig.hashCode()
                        == CAR_ACTIVATION_VOLUME_CONFIG.hashCode()).isTrue();
    }
}
