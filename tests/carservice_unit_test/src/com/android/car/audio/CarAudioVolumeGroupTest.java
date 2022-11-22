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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioVolumeGroupTest {
    private static final int ZONE_ID = 0;
    private static final int GROUP_ID = 0;
    private static final int STEP_VALUE = 2;
    private static final int MIN_GAIN = 3;
    private static final int MAX_GAIN = 10;
    private static final int DEFAULT_GAIN = 5;
    private static final int DEFAULT_GAIN_INDEX = (DEFAULT_GAIN - MIN_GAIN) / STEP_VALUE;
    private static final int MIN_GAIN_INDEX = 0;
    private static final int MAX_GAIN_INDEX = (MAX_GAIN - MIN_GAIN) / STEP_VALUE;
    private static final int TEST_GAIN_INDEX = 2;
    private static final int TEST_USER_11 = 11;
    private static final String GROUP_NAME = "group_0";
    private static final String MEDIA_DEVICE_ADDRESS = "music";
    private static final String NAVIGATION_DEVICE_ADDRESS = "navigation";

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    private static final @CarAudioContext.AudioContext int TEST_MEDIA_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
    private static final @CarAudioContext.AudioContext int TEST_ALARM_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_ALARM));
    private static final @CarAudioContext.AudioContext int TEST_CALL_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION));
    private static final @CarAudioContext.AudioContext int TEST_CALL_RING_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_RINGTONE));
    private static final @CarAudioContext.AudioContext int TEST_NAVIGATION_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE));
    private static final @CarAudioContext.AudioContext int TEST_NOTIFICATION_CONTEXT_ID =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_NOTIFICATION));

    private CarAudioDeviceInfo mMediaDeviceInfo;
    private CarAudioDeviceInfo mNavigationDeviceInfo;

    @Mock
    CarAudioSettings mSettingsMock;

    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setUp() {
        mMediaDeviceInfo = new InfoBuilder(MEDIA_DEVICE_ADDRESS).build();
        mNavigationDeviceInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS).build();
    }

    @Test
    public void testInitializedIndexes() {
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        expect.withMessage("Min index").that(carVolumeGroup.getMinGainIndex())
                .isEqualTo(MIN_GAIN_INDEX);
        expect.withMessage("Max index").that(carVolumeGroup.getMaxGainIndex())
                .isEqualTo(MAX_GAIN_INDEX);
        expect.withMessage("Default index").that(carVolumeGroup.getDefaultGainIndex())
                .isEqualTo(DEFAULT_GAIN_INDEX);
    }

    @Test
    public void checkValidGainIndexes() {
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        expect.withMessage("Min index").that(carVolumeGroup.isValidGainIndex(MIN_GAIN_INDEX))
                .isTrue();
        expect.withMessage("Max index").that(carVolumeGroup.isValidGainIndex(MAX_GAIN_INDEX))
                .isTrue();
        expect.withMessage("Default index")
                .that(carVolumeGroup.isValidGainIndex(DEFAULT_GAIN_INDEX))
                .isTrue();

        expect.withMessage("Outside range index")
                .that(carVolumeGroup.isValidGainIndex(MIN_GAIN_INDEX - 1))
                .isFalse();
        expect.withMessage("Outside range index")
                .that(carVolumeGroup.isValidGainIndex(MAX_GAIN_INDEX + 1))
                .isFalse();
    }

    @Test
    public void setCurrentGainIndex_setsGainOnAllBoundDevices() {
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup();
        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        verify(mMediaDeviceInfo).setCurrentGain(7);
        verify(mNavigationDeviceInfo).setCurrentGain(7);
    }

    @Test
    public void setCurrentGainIndex_updatesCurrentGainIndex() {
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup();
        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        verify(mSettingsMock).storeVolumeGainIndexForUser(
                anyInt(), eq(0), eq(0), eq(TEST_GAIN_INDEX));
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForUser() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setGainIndexForUser(TEST_USER_11).build();
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup(settings);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_11);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings).storeVolumeGainIndexForUser(TEST_USER_11, 0, 0, MIN_GAIN);
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForDefaultUser() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setGainIndexForUser(UserHandle.USER_CURRENT).build();
        CarAudioVolumeGroup carVolumeGroup = testVolumeGroupSetup(settings);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings)
                .storeVolumeGainIndexForUser(UserHandle.USER_CURRENT, 0, 0, MIN_GAIN);
    }

    private CarAudioVolumeGroup testVolumeGroupSetup() {
        return testVolumeGroupSetup(mSettingsMock);
    }

    private CarAudioVolumeGroup testVolumeGroupSetup(CarAudioSettings settings) {
        SparseArray<String> contextToAddress = new SparseArray<>();
        ArrayMap<String, CarAudioDeviceInfo> addressToCarAudioDeviceInfo = new ArrayMap<>();

        addressToCarAudioDeviceInfo.put(mMediaDeviceInfo.getAddress(), mMediaDeviceInfo);
        contextToAddress.put(TEST_MEDIA_CONTEXT_ID, mMediaDeviceInfo.getAddress());
        contextToAddress.put(TEST_CALL_CONTEXT_ID, mMediaDeviceInfo.getAddress());
        contextToAddress.put(TEST_CALL_RING_CONTEXT_ID, mMediaDeviceInfo.getAddress());

        addressToCarAudioDeviceInfo.put(mNavigationDeviceInfo.getAddress(), mNavigationDeviceInfo);
        contextToAddress.put(TEST_NAVIGATION_CONTEXT_ID, mNavigationDeviceInfo.getAddress());
        contextToAddress.put(TEST_ALARM_CONTEXT_ID, mNavigationDeviceInfo.getAddress());
        contextToAddress.put(TEST_NOTIFICATION_CONTEXT_ID, mNavigationDeviceInfo.getAddress());

        return new CarAudioVolumeGroup(TEST_CAR_AUDIO_CONTEXT, settings,
                contextToAddress, addressToCarAudioDeviceInfo, ZONE_ID, GROUP_ID, GROUP_NAME,
                STEP_VALUE, DEFAULT_GAIN, MIN_GAIN, MAX_GAIN, /* useCoreAudioVolume= */ false);
    }

    private static final class SettingsBuilder {
        private final SparseIntArray mStoredGainIndexes = new SparseIntArray();
        private final SparseBooleanArray mStoreMuteStates = new SparseBooleanArray();
        private final int mZoneId;
        private final int mGroupId;

        private boolean mPersistMute;

        SettingsBuilder(int zoneId, int groupId) {
            mZoneId = zoneId;
            mGroupId = groupId;
        }

        SettingsBuilder setGainIndexForUser(@UserIdInt int userId) {
            mStoredGainIndexes.put(userId, TEST_GAIN_INDEX);
            return this;
        }

        CarAudioSettings build() {
            CarAudioSettings settingsMock = Mockito.mock(CarAudioSettings.class);
            for (int storeIndex = 0; storeIndex < mStoredGainIndexes.size(); storeIndex++) {
                int gainUserId = mStoredGainIndexes.keyAt(storeIndex);
                when(settingsMock
                        .getStoredVolumeGainIndexForUser(gainUserId, mZoneId,
                                mGroupId)).thenReturn(
                        mStoredGainIndexes.get(gainUserId, DEFAULT_GAIN));
            }
            for (int muteIndex = 0; muteIndex < mStoreMuteStates.size(); muteIndex++) {
                int muteUserId = mStoreMuteStates.keyAt(muteIndex);
                when(settingsMock.getVolumeGroupMuteForUser(muteUserId, mZoneId, mGroupId))
                        .thenReturn(mStoreMuteStates.get(muteUserId, false));
                when(settingsMock.isPersistVolumeGroupMuteEnabled(muteUserId))
                        .thenReturn(mPersistMute);
            }
            return settingsMock;
        }
    }

    private static final class InfoBuilder {
        private final String mAddress;

        private int mStepValue = STEP_VALUE;
        private int mDefaultGain = DEFAULT_GAIN;
        private int mMinGain = MIN_GAIN;
        private int mMaxGain = MAX_GAIN;

        InfoBuilder(String address) {
            mAddress = address;
        }

        CarAudioDeviceInfo build() {
            CarAudioDeviceInfo infoMock = Mockito.mock(CarAudioDeviceInfo.class);
            when(infoMock.getStepValue()).thenReturn(mStepValue);
            when(infoMock.getDefaultGain()).thenReturn(mDefaultGain);
            when(infoMock.getMaxGain()).thenReturn(mMaxGain);
            when(infoMock.getMinGain()).thenReturn(mMinGain);
            when(infoMock.getAddress()).thenReturn(mAddress);
            return infoMock;
        }
    }
}
