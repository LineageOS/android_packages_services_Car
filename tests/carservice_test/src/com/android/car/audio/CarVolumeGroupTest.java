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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.os.UserHandle;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.primitives.Ints;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarVolumeGroupTest extends AbstractExtendedMockitoTestCase{
    private static final int STEP_VALUE = 2;
    private static final int MIN_GAIN = 0;
    private static final int MAX_GAIN = 5;
    private static final int DEFAULT_GAIN = 0;
    private static final int TEST_USER_10 = 10;
    private static final int TEST_USER_11 = 11;
    private static final String OTHER_ADDRESS = "other_address";
    private static final String MEDIA_DEVICE_ADDRESS = "music";
    private static final String NAVIGATION_DEVICE_ADDRESS = "navigation";


    private CarAudioDeviceInfo mMediaDevice;
    private CarAudioDeviceInfo mNavigationDevice;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(ActivityManager.class);
    }

    @Before
    public void setUp() {
        mMediaDevice = generateCarAudioDeviceInfo(MEDIA_DEVICE_ADDRESS);
        mNavigationDevice = generateCarAudioDeviceInfo(NAVIGATION_DEVICE_ADDRESS);
    }

    @Test
    public void bind_associatesDeviceAddresses() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        carVolumeGroup.bind(CarAudioContext.MUSIC, mMediaDevice);
        assertEquals(1, carVolumeGroup.getAddresses().size());

        carVolumeGroup.bind(CarAudioContext.NAVIGATION, mNavigationDevice);

        List<String> addresses = carVolumeGroup.getAddresses();
        assertEquals(2, addresses.size());
        assertTrue(addresses.contains(MEDIA_DEVICE_ADDRESS));
        assertTrue(addresses.contains(NAVIGATION_DEVICE_ADDRESS));
    }

    @Test
    public void bind_checksForSameStepSize() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        carVolumeGroup.bind(CarAudioContext.MUSIC, mMediaDevice);
        CarAudioDeviceInfo differentStepValueDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE + 1,
                MIN_GAIN, MAX_GAIN);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.bind(CarAudioContext.NAVIGATION, differentStepValueDevice));
        assertThat(thrown).hasMessageThat()
                .contains("Gain controls within one group must have same step value");
    }

    @Test
    public void bind_updatesMinGainToSmallestValue() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        CarAudioDeviceInfo largestMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 10, 10);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, largestMinGain);

        assertEquals(0, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo smallestMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 2, 10);
        carVolumeGroup.bind(CarAudioContext.NOTIFICATION, smallestMinGain);

        assertEquals(8, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo middleMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 7, 10);
        carVolumeGroup.bind(CarAudioContext.VOICE_COMMAND, middleMinGain);

        assertEquals(8, carVolumeGroup.getMaxGainIndex());
    }

    @Test
    public void bind_updatesMaxGainToLargestValue() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        CarAudioDeviceInfo smallestMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 5);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, smallestMaxGain);

        assertEquals(4, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo largestMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 10);
        carVolumeGroup.bind(CarAudioContext.NOTIFICATION, largestMaxGain);

        assertEquals(9, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo middleMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 7);
        carVolumeGroup.bind(CarAudioContext.VOICE_COMMAND, middleMaxGain);

        assertEquals(9, carVolumeGroup.getMaxGainIndex());
    }

    @Test
    public void bind_checksThatTheSameContextIsNotBoundTwice() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        carVolumeGroup.bind(CarAudioContext.NAVIGATION, mMediaDevice);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.bind(CarAudioContext.NAVIGATION, mMediaDevice));
        assertThat(thrown).hasMessageThat()
                .contains("Context NAVIGATION has already been bound to " + MEDIA_DEVICE_ADDRESS);
    }

    @Test
    public void getContexts_returnsAllContextsBoundToVolumeGroup() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        int[] contexts = carVolumeGroup.getContexts();

        assertEquals(6, contexts.length);

        List<Integer> contextsList = Ints.asList(contexts);
        assertTrue(contextsList.contains(CarAudioContext.MUSIC));
        assertTrue(contextsList.contains(CarAudioContext.CALL));
        assertTrue(contextsList.contains(CarAudioContext.CALL_RING));
        assertTrue(contextsList.contains(CarAudioContext.NAVIGATION));
        assertTrue(contextsList.contains(CarAudioContext.ALARM));
        assertTrue(contextsList.contains(CarAudioContext.NOTIFICATION));
    }

    @Test
    public void getContextsForAddress_returnsContextsBoundToThatAddress() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        List<Integer> contextsList = carVolumeGroup.getContextsForAddress(MEDIA_DEVICE_ADDRESS);

        assertThat(contextsList).containsExactly(CarAudioContext.MUSIC,
                CarAudioContext.CALL, CarAudioContext.CALL_RING);
    }

    @Test
    public void getContextsForAddress_returnsEmptyArrayIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        List<Integer> contextsList = carVolumeGroup.getContextsForAddress(OTHER_ADDRESS);

        assertThat(contextsList).isEmpty();
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsExpectedDevice() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                MEDIA_DEVICE_ADDRESS);

        assertEquals(mMediaDevice, actualDevice);
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsNullIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                OTHER_ADDRESS);

        assertNull(actualDevice);
    }

    @Test
    public void setCurrentGainIndex_setsGainOnAllBoundDevices() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(2);
        verify(mMediaDevice).setCurrentGain(4);
        verify(mNavigationDevice).setCurrentGain(4);
    }

    @Test
    public void setCurrentGainIndex_updatesCurrentGainIndex() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(2);

        assertEquals(2, carVolumeGroup.getCurrentGainIndex());
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsAboveMin() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.setCurrentGainIndex(-1));
        assertThat(thrown).hasMessageThat().contains("Gain out of range (0:5) -2index -1");
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsBelowMax() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.setCurrentGainIndex(3));
        assertThat(thrown).hasMessageThat().contains("Gain out of range (0:5) 6index 3");
    }

    @Test
    public void getMinGainIndex_alwaysReturnsZero() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);
        CarAudioDeviceInfo minGainPlusOneDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, 10, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, minGainPlusOneDevice);

        assertEquals(0, carVolumeGroup.getMinGainIndex());

        CarAudioDeviceInfo minGainDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, 1, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NOTIFICATION, minGainDevice);

        assertEquals(0, carVolumeGroup.getMinGainIndex());
    }

    @Test
    public void loadVolumesSettingsForUser_setsCurrentGainIndexForUser() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setGainIndexForUser(TEST_USER_10, 2)
                .setGainIndexForUser(TEST_USER_11, 0)
                .build();

        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);

        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertEquals(2, carVolumeGroup.getCurrentGainIndex());

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_11);

        assertEquals(0, carVolumeGroup.getCurrentGainIndex());
    }

    @Test
    public void loadVolumesSettingsForUser_setsCurrentGainIndexToDefault() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setGainIndexForUser(TEST_USER_10, 10)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);

        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.setCurrentGainIndex(2);

        assertEquals(2, carVolumeGroup.getCurrentGainIndex());

        carVolumeGroup.loadVolumesSettingsForUser(0);

        assertEquals(0, carVolumeGroup.getCurrentGainIndex());
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForUser() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setGainIndexForUser(TEST_USER_11, 2)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);

        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_11);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings).storeVolumeGainIndexForUser(TEST_USER_11, 0, 0, MIN_GAIN);
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForDefaultUser() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setGainIndexForUser(UserHandle.USER_CURRENT, 2)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);

        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings)
                .storeVolumeGainIndexForUser(UserHandle.USER_CURRENT, 0, 0, MIN_GAIN);
    }

    @Test
    public void bind_setsCurrentGainIndexToStoredGainIndex() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);


        assertEquals(2, carVolumeGroup.getCurrentGainIndex());
    }

    @Test
    public void getAddressForContext_returnsExpectedDeviceAddress() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        carVolumeGroup.bind(CarAudioContext.MUSIC, mMediaDevice);

        String mediaAddress = carVolumeGroup.getAddressForContext(CarAudioContext.MUSIC);

        assertEquals(mMediaDevice.getAddress(), mediaAddress);
    }

    @Test
    public void getAddressForContext_returnsNull() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        String nullAddress = carVolumeGroup.getAddressForContext(CarAudioContext.MUSIC);

        assertNull(nullAddress);
    }

    @Test
    public void isMuted_whenDefault_returnsFalse() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        assertThat(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void isMuted_afterMuting_returnsTrue() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();
        carVolumeGroup.setMute(true);

        assertThat(carVolumeGroup.isMuted()).isTrue();
    }

    @Test
    public void isMuted_afterUnMuting_returnsFalse() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();
        carVolumeGroup.setMute(false);

        assertThat(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void setMute_withMutedState_storesValueToSetting() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setMuteForUser(TEST_USER_10, false)
                .setIsPersistVolumeGroupEnabled(true)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, true);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        carVolumeGroup.setMute(true);

        verify(settings)
                .storeVolumeGroupMuteForUser(TEST_USER_10, 0, 0, true);
    }

    @Test
    public void setMute_withUnMutedState_storesValueToSetting() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setMuteForUser(TEST_USER_10, false)
                .setIsPersistVolumeGroupEnabled(true)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, true);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        carVolumeGroup.setMute(false);

        verify(settings)
                .storeVolumeGroupMuteForUser(TEST_USER_10, 0, 0, false);
    }

    @Test
    public void loadVolumesSettingsForUser_withMutedState_loadsMuteStateForUser() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteForUser(true, true,
                TEST_USER_10);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertEquals(true, carVolumeGroup.isMuted());
    }

    @Test
    public void loadVolumesSettingsForUser_withDisabledUseVolumeGroupMute_doesNotLoadMute() {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setMuteForUser(TEST_USER_10, true)
                .setIsPersistVolumeGroupEnabled(true)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertEquals(false, carVolumeGroup.isMuted());
    }

    @Test
    public void loadVolumesSettingsForUser_withUnMutedState_loadsMuteStateForUser() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteForUser(false, true,
                TEST_USER_10);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertEquals(false, carVolumeGroup.isMuted());
    }

    @Test
    public void loadVolumesSettingsForUser_withMutedStateAndNoPersist_returnsDefaultMuteState() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteForUser(true, false,
                TEST_USER_10);
        CarAudioDeviceInfo deviceInfo = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, MIN_GAIN, MAX_GAIN);
        carVolumeGroup.bind(CarAudioContext.NAVIGATION, deviceInfo);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertEquals(false, carVolumeGroup.isMuted());
    }

    CarVolumeGroup getVolumeGroupWithGainAndUser(int gain, @UserIdInt int userId) {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setGainIndexForUser(userId, gain)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, false);

        return carVolumeGroup;
    }

    CarVolumeGroup getVolumeGroupWithMuteForUser(boolean isMuted, boolean persistMute,
            @UserIdInt int userId) {
        CarAudioSettings settings = new CarVolumeGroupSettingsBuilder(0, 0)
                .setMuteForUser(userId, isMuted)
                .setIsPersistVolumeGroupEnabled(persistMute)
                .build();
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(0, 0, settings, true);

        return carVolumeGroup;
    }

    private CarVolumeGroup testVolumeGroupSetup() {
        CarVolumeGroup carVolumeGroup =
                getVolumeGroupWithGainAndUser(2, UserHandle.USER_CURRENT);

        carVolumeGroup.bind(CarAudioContext.MUSIC, mMediaDevice);
        carVolumeGroup.bind(CarAudioContext.CALL, mMediaDevice);
        carVolumeGroup.bind(CarAudioContext.CALL_RING, mMediaDevice);

        carVolumeGroup.bind(CarAudioContext.NAVIGATION, mNavigationDevice);
        carVolumeGroup.bind(CarAudioContext.ALARM, mNavigationDevice);
        carVolumeGroup.bind(CarAudioContext.NOTIFICATION, mNavigationDevice);

        return carVolumeGroup;
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address) {
        return generateCarAudioDeviceInfo(address, STEP_VALUE, MIN_GAIN, MAX_GAIN);
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address, int stepValue,
            int minGain, int maxGain) {
        CarAudioDeviceInfo cadiMock = Mockito.mock(CarAudioDeviceInfo.class);
        when(cadiMock.getStepValue()).thenReturn(stepValue);
        when(cadiMock.getDefaultGain()).thenReturn(DEFAULT_GAIN);
        when(cadiMock.getMaxGain()).thenReturn(maxGain);
        when(cadiMock.getMinGain()).thenReturn(minGain);
        when(cadiMock.getAddress()).thenReturn(address);
        return cadiMock;
    }

    private static final class CarVolumeGroupSettingsBuilder {
        private SparseIntArray mStoredGainIndexes = new SparseIntArray();
        private SparseBooleanArray mStoreMuteStates = new SparseBooleanArray();
        private boolean mPersistMute;
        private final int mZoneId;
        private final int mGroupId;

        CarVolumeGroupSettingsBuilder(int zoneId, int groupId) {
            mZoneId = zoneId;
            mGroupId = groupId;
        }

        CarVolumeGroupSettingsBuilder setGainIndexForUser(@UserIdInt int userId, int gainIndex) {
            mStoredGainIndexes.put(userId, gainIndex);
            return this;
        }

        CarVolumeGroupSettingsBuilder setMuteForUser(@UserIdInt int userId, boolean mute) {
            mStoreMuteStates.put(userId, mute);
            return this;
        }

        CarVolumeGroupSettingsBuilder setIsPersistVolumeGroupEnabled(boolean persistMute) {
            mPersistMute = persistMute;
            return this;
        }

        CarAudioSettings build() {
            CarAudioSettings settingsMock = Mockito.mock(CarAudioSettings.class);
            for (int storeIndex = 0; storeIndex < mStoredGainIndexes.size(); storeIndex++) {
                int gainUserId = mStoredGainIndexes.keyAt(storeIndex);
                when(settingsMock
                        .getStoredVolumeGainIndexForUser(gainUserId, mZoneId,
                        mGroupId)).thenReturn(mStoredGainIndexes.get(gainUserId, DEFAULT_GAIN));
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
}
