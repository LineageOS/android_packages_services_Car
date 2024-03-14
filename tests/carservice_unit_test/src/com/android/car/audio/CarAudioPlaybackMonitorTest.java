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

import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.car.test.AbstractExpectableTestCase;
import android.media.AudioAttributes;
import android.util.Pair;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioPlaybackMonitorTest extends AbstractExpectableTestCase {

    private static final int PRIMARY_ZONE_ID = 0;
    private static final int SECONDARY_ZONE_ID = 1;
    private static final int DEFAULT_ZONE_CONFIG_ID = 0;
    private static final int DEFAULT_MEDIA_GROUP_ID = 0;
    private static final int DEFAULT_NAVIGATION_GROUP_ID = 1;
    private static final int PLAYBACK_UID_1 = 10101;
    private static final int PLAYBACK_UID_2 = 10102;
    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes TEST_NAVIGATION_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build();

    private CarAudioZone mPrimaryZone;
    private CarAudioZone mSecondaryZone;
    private SparseArray<CarAudioZone> mCarAudioZones = new SparseArray<>();
    private CarAudioPlaybackMonitor mCarAudioPlaybackMonitor;

    @Mock
    private CarVolumeGroup mMockPrimaryZoneMediaGroup;
    @Mock
    private CarVolumeGroup mMockPrimaryZoneNavGroup;
    @Mock
    private CarVolumeGroup mMockSecondaryZoneMediaGroup;
    @Mock
    private CarVolumeGroup mMockSecondaryZoneNavGroup;
    @Mock
    private CarAudioService mMockCarAudioService;
    @Captor
    private ArgumentCaptor<List<CarAudioPlaybackMonitor.ActivationInfo>> mActivationInfoCaptor;

    @Before
    public void setup() {
        mPrimaryZone = generatePrimaryZone();
        mSecondaryZone = generateSecondaryZone();
        mCarAudioZones.put(PRIMARY_ZONE_ID, mPrimaryZone);
        mCarAudioZones.put(SECONDARY_ZONE_ID, mSecondaryZone);
        mCarAudioPlaybackMonitor = new CarAudioPlaybackMonitor(mMockCarAudioService,
                mCarAudioZones);
    }

    @Test
    public void construct_withNullCarAudioService_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioPlaybackMonitor(/* carAudioService= */ null,
                        mCarAudioZones));

        expectWithMessage("Null car audio service exception")
                .that(thrown).hasMessageThat().contains("Car audio service can not be null");
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_withNullAttributesList() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(
                /* newActivePlaybackAttributesWithUid= */ null, PRIMARY_ZONE_ID);

        verify(mMockCarAudioService, never()).handleActivationVolumeWithActivationInfos(any(),
                eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_withEmptyAttributesList() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(Collections.emptyList(),
                PRIMARY_ZONE_ID);

        verify(mMockCarAudioService, never()).handleActivationVolumeWithActivationInfos(any(),
                eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_forPlaybacksInZoneForFirstTime() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_1),
                new Pair<>(TEST_NAVIGATION_AUDIO_ATTRIBUTE, PLAYBACK_UID_2)), PRIMARY_ZONE_ID);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos for playback configuration")
                .that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT),
                        new CarAudioPlaybackMonitor.ActivationInfo(DEFAULT_NAVIGATION_GROUP_ID,
                                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT));
    }

    private CarAudioZone generatePrimaryZone() {
        doReturn(DEFAULT_MEDIA_GROUP_ID).when(mMockPrimaryZoneMediaGroup).getId();
        doReturn(DEFAULT_NAVIGATION_GROUP_ID).when(mMockPrimaryZoneNavGroup).getId();
        doReturn(false).when(mMockPrimaryZoneMediaGroup).hasAudioAttributes(any());
        doReturn(false).when(mMockPrimaryZoneNavGroup).hasAudioAttributes(any());
        doReturn(true).when(mMockPrimaryZoneMediaGroup)
                .hasAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE);
        doReturn(true).when(mMockPrimaryZoneNavGroup)
                .hasAudioAttributes(TEST_NAVIGATION_AUDIO_ATTRIBUTE);
        CarAudioZoneConfig primaryCarAudioZoneConfig =
                new CarAudioZoneConfig.Builder("Primary zone config 0", PRIMARY_ZONE_ID,
                        DEFAULT_ZONE_CONFIG_ID, /* isDefault= */ true)
                        .addVolumeGroup(mMockPrimaryZoneMediaGroup)
                        .addVolumeGroup(mMockPrimaryZoneNavGroup).build();
        return new TestCarAudioZoneBuilder("Primary zone", PRIMARY_ZONE_ID)
                .addCarAudioZoneConfig(primaryCarAudioZoneConfig).build();
    }

    private CarAudioZone generateSecondaryZone() {
        doReturn(DEFAULT_MEDIA_GROUP_ID).when(mMockSecondaryZoneMediaGroup).getId();
        doReturn(DEFAULT_NAVIGATION_GROUP_ID).when(mMockSecondaryZoneNavGroup).getId();
        doReturn(false).when(mMockSecondaryZoneMediaGroup).hasAudioAttributes(any());
        doReturn(false).when(mMockSecondaryZoneNavGroup).hasAudioAttributes(any());
        doReturn(true).when(mMockSecondaryZoneMediaGroup)
                .hasAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE);
        doReturn(true).when(mMockSecondaryZoneNavGroup)
                .hasAudioAttributes(TEST_NAVIGATION_AUDIO_ATTRIBUTE);
        CarAudioZoneConfig secondaryCarAudioZoneConfig =
                new CarAudioZoneConfig.Builder("Secondary zone config 0", SECONDARY_ZONE_ID,
                        DEFAULT_ZONE_CONFIG_ID, /* isDefault= */ true)
                        .addVolumeGroup(mMockSecondaryZoneMediaGroup)
                        .addVolumeGroup(mMockSecondaryZoneNavGroup).build();
        return new TestCarAudioZoneBuilder("Secondary zone", SECONDARY_ZONE_ID)
                .addCarAudioZoneConfig(secondaryCarAudioZoneConfig).build();
    }
}
