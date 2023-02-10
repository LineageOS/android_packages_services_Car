/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_ATTENUATED;
import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_BLOCKED;
import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_LIMITED;
import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE;
import static com.android.car.audio.CarVolumeEventFlag.FLAG_EVENT_VOLUME_NONE;

import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class CarVolumeEventFlagTest {

    @Parameterized.Parameters
    public static Collection provideParams() {
        return List.of(
                new Object[][]{
                        {FLAG_EVENT_VOLUME_NONE, "FLAG_EVENT_VOLUME_NONE",
                                /* expectedValid= */ false},
                        {FLAG_EVENT_VOLUME_CHANGE, "FLAG_EVENT_VOLUME_CHANGE",
                                /* expectedValid= */ false},
                        {FLAG_EVENT_VOLUME_MUTE, "FLAG_EVENT_VOLUME_MUTE",
                                /* expectedValid= */ false},
                        {FLAG_EVENT_VOLUME_BLOCKED, "FLAG_EVENT_VOLUME_BLOCKED",
                                /* expectedValid= */ false},
                        {FLAG_EVENT_VOLUME_ATTENUATED, "FLAG_EVENT_VOLUME_ATTENUATED",
                                /* expectedValid= */ false},
                        {FLAG_EVENT_VOLUME_LIMITED, "FLAG_EVENT_VOLUME_LIMITED",
                                /* expectedValid= */ false},
                        {/* flags= */ 0, /* name= */ "", /* expectedValid= */ true},
                        {/* flags= */ 1 << 20, /* name= */ "1048576", /* expectedValid= */ true},
                });
    }

    @CarVolumeEventFlag.VolumeEventFlags private final int mFlags;
    private final String mName;
    private final boolean mExpectedValid;
    public CarVolumeEventFlagTest(@CarVolumeEventFlag.VolumeEventFlags int flags, String name,
            boolean expectedValid) {
        mFlags = flags;
        mName = name;
        mExpectedValid = expectedValid;
    }

    @Test
    public void hasInvalidFlags() {
        boolean hasInvalidFlags = CarVolumeEventFlag.hasInvalidFlag(mFlags);

        assertWithMessage("Valid flag for flags %s", mFlags)
                .that(hasInvalidFlags).isEqualTo(mExpectedValid);
    }

    @Test
    public void flagsToString() {
        assertWithMessage("String name for flag %s", mFlags)
                .that(CarVolumeEventFlag.flagsToString(mFlags)).isEqualTo(mName);
    }
}
