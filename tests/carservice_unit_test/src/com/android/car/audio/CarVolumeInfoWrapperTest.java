/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;

import static com.android.car.audio.CarAudioContext.CALL;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CarVolumeInfoWrapperTest {

    private static final int TEST_GROUP_ID = 0;
    private static final int TEST_GROUP_VOLUME = 5;
    private static final int TEST_MIN_GROUP_VOLUME = 0;
    private static final int TEST_MAX_GROUP_VOLUME = 9000;
    private static final boolean TEST_VOLUME_GROUP_MUTE = true;

    private CarVolumeInfoWrapper mCarVolumeInfoWrapper;

    @Before
    public void setUp() {
        CarAudioService carAudioService = mock(CarAudioService.class);

        when(carAudioService.getSuggestedAudioContextForPrimaryZone()).thenReturn(CALL);
        when(carAudioService.getVolumeGroupIdForAudioContext(PRIMARY_AUDIO_ZONE, CALL))
                .thenReturn(TEST_GROUP_ID);
        when(carAudioService.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID))
                .thenReturn(TEST_GROUP_VOLUME);
        when(carAudioService.getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID))
                .thenReturn(TEST_MIN_GROUP_VOLUME);
        when(carAudioService.getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID))
                .thenReturn(TEST_MAX_GROUP_VOLUME);
        when(carAudioService.isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID))
                .thenReturn(TEST_VOLUME_GROUP_MUTE);

        mCarVolumeInfoWrapper = new CarVolumeInfoWrapper(carAudioService);
    }

    @Test
    public void constructor_withNullServices_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new CarVolumeInfoWrapper(null));

        assertWithMessage("Car Audio Info Construction")
                .that(thrown).hasMessageThat().contains("Car Audio Service");
    }

    @Test
    public void getSuggestedAudioContextForPrimaryZone_returnsAudioContext() {
        int context = mCarVolumeInfoWrapper.getSuggestedAudioContextForPrimaryZone();

        assertWithMessage("Car Audio Context")
                .that(context).isEqualTo(CALL);
    }

    @Test
    public void getVolumeGroupIdForAudioContext_returnsGroupIdForContext() {
        int groupId = mCarVolumeInfoWrapper
                .getVolumeGroupIdForAudioContext(PRIMARY_AUDIO_ZONE, CALL);

        assertWithMessage("Car Audio Group Id")
                .that(groupId).isEqualTo(TEST_GROUP_ID);
    }

    @Test
    public void getGroupVolume_returnsVolumeGroup() {
        int groupVolume = mCarVolumeInfoWrapper.getGroupVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID);

        assertWithMessage("Current Car Audio Group Volume")
                .that(groupVolume).isEqualTo(TEST_GROUP_VOLUME);
    }

    @Test
    public void getGroupMinVolume_returnsMinVolume() {
        int groupMinVolume = mCarVolumeInfoWrapper
                .getGroupMinVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID);

        assertWithMessage("Car Audio Group Min Volume")
                .that(groupMinVolume).isEqualTo(TEST_MIN_GROUP_VOLUME);
    }

    @Test
    public void getGroupMaxVolume_returnsMaxVolume() {
        int groupMaxVolume = mCarVolumeInfoWrapper
                .getGroupMaxVolume(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID);

        assertWithMessage("Car Audio Group Max Volume")
                .that(groupMaxVolume).isEqualTo(TEST_MAX_GROUP_VOLUME);
    }

    @Test
    public void isVolumeGroupMuted_returnsGroupMuteState() {
        boolean isVolumeGroupMuted = mCarVolumeInfoWrapper
                .isVolumeGroupMuted(PRIMARY_AUDIO_ZONE, TEST_GROUP_ID);

        assertWithMessage("Car Audio Group Volume Mute")
                .that(isVolumeGroupMuted).isEqualTo(TEST_VOLUME_GROUP_MUTE);
    }
}
