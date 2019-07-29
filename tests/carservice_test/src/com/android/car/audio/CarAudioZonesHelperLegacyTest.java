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
package com.android.car.audio;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.automotive.audiocontrol.V1_0.IAudioControl;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.collect.Lists;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarAudioZonesHelperLegacyTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    @Test
    public void constructor_checksForNoDuplicateBusNumbers() {
        Context context = ApplicationProvider.getApplicationContext();

        IAudioControl mockAudioControl = Mockito.mock(IAudioControl.class);

        CarAudioDeviceInfo deviceInfo1 = Mockito.mock(CarAudioDeviceInfo.class);
        when(deviceInfo1.getAddress()).thenReturn("bus001_media");
        CarAudioDeviceInfo deviceInfo2 = Mockito.mock(CarAudioDeviceInfo.class);
        when(deviceInfo2.getAddress()).thenReturn("bus001_notifications");
        List<CarAudioDeviceInfo> carAudioDeviceInfos = Lists.newArrayList(deviceInfo1, deviceInfo2);

        thrown.expect(RuntimeException.class);
        thrown.expectMessage(
                "Two addresses map to same bus number: bus001_notifications and bus001_media");

        new CarAudioZonesHelperLegacy(context, 1,
                carAudioDeviceInfos, mockAudioControl);
    }
}
