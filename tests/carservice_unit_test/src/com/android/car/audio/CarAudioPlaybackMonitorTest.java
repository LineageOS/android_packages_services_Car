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
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.car.test.AbstractExpectableTestCase;
import android.media.AudioAttributes;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
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
    private static final int DEFAULT_RINGTONE_GROUP_ID = 2;
    private static final int PLAYBACK_UID_1 = 10101;
    private static final int PLAYBACK_UID_2 = 10102;
    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes TEST_NAVIGATION_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build();
    private static final AudioAttributes TEST_NOTIFICATION_RINGTONE_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build();
    private static final AudioAttributes TEST_VOICE_COMMUNICATION_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION).build();

    private CarAudioZone mPrimaryZone;
    private CarAudioZone mSecondaryZone;
    private SparseArray<CarAudioZone> mCarAudioZones = new SparseArray<>();
    private CarAudioPlaybackMonitor mCarAudioPlaybackMonitor;

    @Mock
    private CarVolumeGroup mMockPrimaryZoneMediaGroup;
    @Mock
    private CarVolumeGroup mMockPrimaryZoneNavGroup;
    @Mock
    private CarVolumeGroup mMockPrimaryZoneRingtoneGroup;
    @Mock
    private CarVolumeGroup mMockSecondaryZoneMediaGroup;
    @Mock
    private CarVolumeGroup mMockSecondaryZoneNavGroup;
    @Mock
    private CarAudioService mMockCarAudioService;
    @Mock
    private TelephonyManager mMockTelephonyManager;
    @Captor
    private ArgumentCaptor<List<CarAudioPlaybackMonitor.ActivationInfo>> mActivationInfoCaptor;

    @Before
    public void setup() {
        mPrimaryZone = generatePrimaryZone();
        mSecondaryZone = generateSecondaryZone();
        mCarAudioZones.put(PRIMARY_ZONE_ID, mPrimaryZone);
        mCarAudioZones.put(SECONDARY_ZONE_ID, mSecondaryZone);
        mCarAudioPlaybackMonitor = new CarAudioPlaybackMonitor(mMockCarAudioService,
                mCarAudioZones, mMockTelephonyManager);
    }

    @Test
    public void construct_withNullCarAudioService_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioPlaybackMonitor(/* carAudioService= */ null,
                        mCarAudioZones, mMockTelephonyManager));

        expectWithMessage("Car playback monitor construction exception with null "
                + "car audio service").that(thrown).hasMessageThat()
                .contains("Car audio service can not be null");
    }

    @Test
    public void construct_withNullCarAudioZones_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioPlaybackMonitor(mMockCarAudioService,
                        /* carAudioZones= */ null, mMockTelephonyManager));

        expectWithMessage("Car playback monitor construction exception with null "
                + "car audio zones").that(thrown).hasMessageThat()
                .contains("Car audio zones can not be null");
    }

    @Test
    public void construct_withNullTelephonyManager_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioPlaybackMonitor(mMockCarAudioService,
                        mCarAudioZones, /* telephonyManager= */ null));

        expectWithMessage("Car playback monitor construction exception with null "
                + "telephony manager").that(thrown).hasMessageThat()
                .contains("Telephony manager can not be null");
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

    @Test
    public void onActiveAudioPlaybackAttributesAdded_forAudioAttributesOfFirstTime() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_NAVIGATION_AUDIO_ATTRIBUTE, PLAYBACK_UID_1)), PRIMARY_ZONE_ID);
        reset(mMockCarAudioService);

        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_2)), PRIMARY_ZONE_ID);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback configuration for the first-time media"
                + " attributes").that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(
                        DEFAULT_MEDIA_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT));
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_withSourceChanged() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_1)), PRIMARY_ZONE_ID);
        reset(mMockCarAudioService);

        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_2)), PRIMARY_ZONE_ID);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback configuration with playback source"
                + " changed").that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED));
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_withPlaybackChanged() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_1)), PRIMARY_ZONE_ID);
        reset(mMockCarAudioService);

        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_MEDIA_AUDIO_ATTRIBUTE, PLAYBACK_UID_1)), PRIMARY_ZONE_ID);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback configuration with playback changed")
                .that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED));
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_withNoVolumeGroupFound() {
        AudioAttributes ringtoneAudioAttribute =
                new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build();

        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                        new Pair<>(ringtoneAudioAttribute, PLAYBACK_UID_1)), SECONDARY_ZONE_ID);

        verify(mMockCarAudioService, never()).handleActivationVolumeWithActivationInfos(any(),
                anyInt(), anyInt());
    }

    @Test
    public void onActiveAudioPlaybackAttributesAdded_afterActivationTypesForZonesReset() {
        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_NAVIGATION_AUDIO_ATTRIBUTE, PLAYBACK_UID_1)), PRIMARY_ZONE_ID);
        reset(mMockCarAudioService);
        mCarAudioPlaybackMonitor.resetActivationTypesForZone(PRIMARY_ZONE_ID);

        mCarAudioPlaybackMonitor.onActiveAudioPlaybackAttributesAdded(List.of(
                new Pair<>(TEST_NAVIGATION_AUDIO_ATTRIBUTE, PLAYBACK_UID_2)), PRIMARY_ZONE_ID);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback configuration after "
                + "activation types of zones reset").that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(
                        DEFAULT_NAVIGATION_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT));
    }

    @Test
    public void onCallStateChanged_withRingingState() {
        TelephonyCallback.CallStateListener carCallStateListener = getCallStateListener();

        carCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_RINGING);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback with telephony ringing state")
                .that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(
                        DEFAULT_RINGTONE_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT));
    }

    @Test
    public void onCallStateChanged_withOffHookState() {
        TelephonyCallback.CallStateListener carCallStateListener = getCallStateListener();

        carCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);

        verify(mMockCarAudioService).handleActivationVolumeWithActivationInfos(
                mActivationInfoCaptor.capture(), eq(PRIMARY_ZONE_ID), eq(DEFAULT_ZONE_CONFIG_ID));
        expectWithMessage("Activation infos of playback with telephony off-hook state")
                .that(mActivationInfoCaptor.getValue())
                .containsExactly(new CarAudioPlaybackMonitor.ActivationInfo(
                        DEFAULT_RINGTONE_GROUP_ID,
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT));
    }

    @Test
    public void onCallStateChanged_withIdleState() {
        TelephonyCallback.CallStateListener carCallStateListener = getCallStateListener();
        carCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);

        verify(mMockCarAudioService, never()).handleActivationVolumeWithActivationInfos(
                any(), anyInt(), anyInt());
    }

    @Test
    public void equals_withActivationInfoWithDifferentTypeObject() {
        CarAudioPlaybackMonitor.ActivationInfo activationInfo = new CarAudioPlaybackMonitor
                .ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT);

        expectWithMessage("Activation info").that(activationInfo).isNotEqualTo(mPrimaryZone);
    }

    @Test
    public void equals_withDifferentActivationInfo() {
        CarAudioPlaybackMonitor.ActivationInfo activationInfo1 = new CarAudioPlaybackMonitor
                .ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT);
        CarAudioPlaybackMonitor.ActivationInfo activationInfo2 = new CarAudioPlaybackMonitor
                .ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED);

        expectWithMessage("Activation info with different invocation types")
                .that(activationInfo1).isNotEqualTo(activationInfo2);
    }

    @Test
    public void hashCode_forActivationInfo() {
        CarAudioPlaybackMonitor.ActivationInfo activationInfo1 = new CarAudioPlaybackMonitor
                .ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT);
        CarAudioPlaybackMonitor.ActivationInfo activationInfo2 = new CarAudioPlaybackMonitor
                .ActivationInfo(DEFAULT_MEDIA_GROUP_ID,
                CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT);

        expectWithMessage("Hash code of activation info").that(activationInfo1.hashCode())
                .isEqualTo(activationInfo2.hashCode());
    }

    private CarAudioZone generatePrimaryZone() {
        doReturn(DEFAULT_MEDIA_GROUP_ID).when(mMockPrimaryZoneMediaGroup).getId();
        doReturn(DEFAULT_NAVIGATION_GROUP_ID).when(mMockPrimaryZoneNavGroup).getId();
        doReturn(DEFAULT_RINGTONE_GROUP_ID).when(mMockPrimaryZoneRingtoneGroup).getId();
        doReturn(false).when(mMockPrimaryZoneMediaGroup).hasAudioAttributes(any());
        doReturn(false).when(mMockPrimaryZoneNavGroup).hasAudioAttributes(any());
        doReturn(false).when(mMockPrimaryZoneRingtoneGroup).hasAudioAttributes(any());
        doReturn(true).when(mMockPrimaryZoneMediaGroup)
                .hasAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE);
        doReturn(true).when(mMockPrimaryZoneNavGroup)
                .hasAudioAttributes(TEST_NAVIGATION_AUDIO_ATTRIBUTE);
        doReturn(true).when(mMockPrimaryZoneRingtoneGroup)
                .hasAudioAttributes(TEST_NOTIFICATION_RINGTONE_AUDIO_ATTRIBUTE);
        doReturn(true).when(mMockPrimaryZoneRingtoneGroup)
                .hasAudioAttributes(TEST_VOICE_COMMUNICATION_AUDIO_ATTRIBUTE);
        CarAudioZoneConfig primaryCarAudioZoneConfig =
                new CarAudioZoneConfig.Builder("Primary zone config 0", PRIMARY_ZONE_ID,
                        DEFAULT_ZONE_CONFIG_ID, /* isDefault= */ true)
                        .addVolumeGroup(mMockPrimaryZoneMediaGroup)
                        .addVolumeGroup(mMockPrimaryZoneNavGroup)
                        .addVolumeGroup(mMockPrimaryZoneRingtoneGroup).build();
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

    private TelephonyCallback.CallStateListener getCallStateListener() {
        ArgumentCaptor<TelephonyCallback> captor =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mMockTelephonyManager).registerTelephonyCallback(any(), captor.capture());
        return (TelephonyCallback.CallStateListener) captor.getValue();
    }
}
