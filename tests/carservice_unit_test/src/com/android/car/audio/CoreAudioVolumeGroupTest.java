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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.builtin.media.AudioManagerHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.util.ArrayMap;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.HashSet;

@RunWith(MockitoJUnitRunner.class)
public final class CoreAudioVolumeGroupTest  extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CoreAudioVolumeGroupTest.class.getSimpleName();

    private static final HashSet<String> OEM_TAG_STRING_SET = new HashSet<>();

    private static final AudioAttributes MUSIC_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private static final int MUSIC_STRATEGY_ID = 777;
    private static final int MUSIC_CAR_GROUP_ID = 0;
    private static final int MUSIC_GROUP_ID = 666;
    private static final String MUSIC_CONTEXT_NAME = "MUSIC_CONTEXT";
    private static final AudioAttributes[] MUSIC_AAS = { MUSIC_ATTRIBUTES };
    private static final String MUSIC_GROUP_NAME = "MUSIC_GROUP";
    private static final int MUSIC_AM_INIT_INDEX = 8;
    private static final int MUSIC_MIN_INDEX = 0;
    private static final int MUSIC_MAX_INDEX = 40;
    private static final CarAudioContextInfo MEDIA_CONTEXT_INFO;
    private static final CarAudioContext MUSIC_CONTEXT;
    private static final String MUSIC_DEVICE_ADDRESS = "MUSIC_DEVICE_ADDRESS";

    private static final AudioAttributes NAV_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private static final int NAV_STRATEGY_ID = 99;
    private static final int NAV_CAR_GROUP_ID = 1;
    private static final String NAV_CONTEXT_NAME = "NAV_CONTEXT";
    private static final AudioAttributes[] NAV_AAS = { NAV_ATTRIBUTES };
    private static final String NAV_GROUP_NAME = "NAV_GROUP";
    private static final int NAV_MIN_INDEX = 5;
    private static final int NAV_MAX_INDEX = 35;
    private static final CarAudioContextInfo NAV_CONTEXT_INFO;
    private static final CarAudioContext NAV_CONTEXT;
    private static final String NAV_DEVICE_ADDRESS = "NAV_DEVICE_ADDRESS";
    private CarAudioDeviceInfo mNavInfoMock = mock(CarAudioDeviceInfo.class);

    private static final AudioAttributes OEM_ATTRIBUTES;
    private static final int OEM_STRATEGY_ID = 1979;
    private static final int OEM_CAR_GROUP_ID = 2;
    private static final String OEM_CONTEXT_NAME = "OEM_CONTEXT";
    private static final String OEM_GROUP_NAME = "OEM_GROUP";
    private static final String OEM_FORMATTED_TAGS = "oem=extension_1979";
    private static final int OEM_MIN_INDEX = 1;
    private static final int OEM_MAX_INDEX = 15;
    private static final CarAudioContextInfo OEM_CONTEXT_INFO;
    private static final CarAudioContext OEM_CONTEXT;
    private static final String OEM_DEVICE_ADDRESS = "OEM_DEVICE_ADDRESS";
    private CarAudioDeviceInfo mOemInfoMock = mock(CarAudioDeviceInfo.class);

    static {
        OEM_TAG_STRING_SET.add(OEM_FORMATTED_TAGS);

        OEM_ATTRIBUTES = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .replaceTags(OEM_TAG_STRING_SET)
                .build();
        AudioAttributes[] oemAas = {OEM_ATTRIBUTES};

        MEDIA_CONTEXT_INFO = new CarAudioContextInfo(
            MUSIC_AAS, MUSIC_CONTEXT_NAME, MUSIC_STRATEGY_ID);
        MUSIC_CONTEXT = new CarAudioContext(
            Arrays.asList(MEDIA_CONTEXT_INFO), /* useCoreAudioRouting= */ true);

        NAV_CONTEXT_INFO = new CarAudioContextInfo(NAV_AAS, NAV_CONTEXT_NAME, NAV_STRATEGY_ID);
        NAV_CONTEXT = new CarAudioContext(
            Arrays.asList(NAV_CONTEXT_INFO), /* useCoreAudioRouting= */ true);

        OEM_CONTEXT_INFO = new CarAudioContextInfo(
            oemAas, OEM_CONTEXT_NAME, OEM_STRATEGY_ID);
        OEM_CONTEXT = new CarAudioContext(
            Arrays.asList(OEM_CONTEXT_INFO), /* useCoreAudioRouting= */ true);
    }

    @Mock
    AudioManager mMockAudioManager;
    @Mock
    CarAudioSettings mSettingsMock;

    private CoreAudioVolumeGroup mMusicCoreAudioVolumeGroup;
    private CoreAudioVolumeGroup mNavCoreAudioVolumeGroup;
    private CoreAudioVolumeGroup mOemCoreAudioVolumeGroup;

    public CoreAudioVolumeGroupTest() {
        super(CoreAudioVolumeGroup.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(CoreAudioHelper.class);
        session.spyStatic(AudioManagerHelper.class);
    }
    void setupMock() {
        doReturn(CoreAudioRoutingUtils.MUSIC_GROUP_ID).when(() ->
                CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.MUSIC_ATTRIBUTES));
        doReturn(MUSIC_ATTRIBUTES)
                .when(() ->
                        CoreAudioHelper.selectAttributesForVolumeGroupName(eq(MUSIC_GROUP_NAME)));
        when(mMockAudioManager.getMinVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getMaxVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MAX_INDEX);
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_AM_INIT_INDEX);

        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_AM_INIT_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);

        doReturn(CoreAudioRoutingUtils.NAV_GROUP_ID)
                .when(() ->
                        CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                                CoreAudioRoutingUtils.NAV_ATTRIBUTES));
        doReturn(NAV_ATTRIBUTES)
                .when(() -> CoreAudioHelper
                        .selectAttributesForVolumeGroupName(eq(NAV_GROUP_NAME)));
        when(mMockAudioManager.getMinVolumeIndexForAttributes(NAV_ATTRIBUTES))
                .thenReturn(NAV_MIN_INDEX);
        when(mMockAudioManager.getMaxVolumeIndexForAttributes(NAV_ATTRIBUTES))
                .thenReturn(NAV_MAX_INDEX);

        doReturn(OEM_ATTRIBUTES)
                .when(() -> CoreAudioHelper
                        .selectAttributesForVolumeGroupName(eq(OEM_GROUP_NAME)));
        when(mMockAudioManager.getMinVolumeIndexForAttributes(OEM_ATTRIBUTES))
                .thenReturn(OEM_MIN_INDEX);
        doReturn(CoreAudioRoutingUtils.OEM_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.OEM_ATTRIBUTES));
        when(mMockAudioManager.getMaxVolumeIndexForAttributes(OEM_ATTRIBUTES))
                .thenReturn(OEM_MAX_INDEX);
    }

    @Before
    public void setUp() {
        setupMock();

        SparseArray<String> musicContextToAddress = new SparseArray<>();
        musicContextToAddress.put(MUSIC_STRATEGY_ID, MUSIC_DEVICE_ADDRESS);
        ArrayMap<String, CarAudioDeviceInfo> musicMap = new ArrayMap<>();
        musicMap.put(MUSIC_DEVICE_ADDRESS, mOemInfoMock);
        mMusicCoreAudioVolumeGroup = new CoreAudioVolumeGroup(mMockAudioManager, MUSIC_CONTEXT,
                mSettingsMock, musicContextToAddress, musicMap,
                PRIMARY_AUDIO_ZONE, MUSIC_CAR_GROUP_ID, MUSIC_GROUP_NAME,
                /* useCarVolumeGroupMute= */ false);

        SparseArray<String> navContextToAddress = new SparseArray<>();
        navContextToAddress.put(NAV_STRATEGY_ID, NAV_DEVICE_ADDRESS);
        ArrayMap<String, CarAudioDeviceInfo> navMap = new ArrayMap<>();
        navMap.put(NAV_DEVICE_ADDRESS, mNavInfoMock);
        mNavCoreAudioVolumeGroup = new CoreAudioVolumeGroup(mMockAudioManager, NAV_CONTEXT,
            mSettingsMock, navContextToAddress, navMap,
            PRIMARY_AUDIO_ZONE, NAV_CAR_GROUP_ID, NAV_GROUP_NAME,
            /* useCarVolumeGroupMute= */ false);

        SparseArray<String> oemContextToAddress = new SparseArray<>();
        oemContextToAddress.put(OEM_STRATEGY_ID, OEM_DEVICE_ADDRESS);
        ArrayMap<String, CarAudioDeviceInfo> oemMap = new ArrayMap<>();
        oemMap.put(OEM_DEVICE_ADDRESS, mOemInfoMock);
        mOemCoreAudioVolumeGroup = new CoreAudioVolumeGroup(mMockAudioManager, OEM_CONTEXT,
                mSettingsMock, oemContextToAddress, oemMap,
                PRIMARY_AUDIO_ZONE, OEM_CAR_GROUP_ID, OEM_GROUP_NAME,
                /* useCarVolumeGroupMute= */ false);
    }

    @Test
    public void getMaxIndex() {
        expectWithMessage("Initial gain index").that(mMusicCoreAudioVolumeGroup.getMaxGainIndex())
                .isEqualTo(MUSIC_MAX_INDEX);
        expectWithMessage("Initial gain index").that(mNavCoreAudioVolumeGroup.getMaxGainIndex())
                .isEqualTo(NAV_MAX_INDEX);
        expectWithMessage("Initial gain index").that(mOemCoreAudioVolumeGroup.getMaxGainIndex())
                .isEqualTo(OEM_MAX_INDEX);
    }

    @Test
    public void getMinIndex() {
        expectWithMessage("Initial gain index").that(mMusicCoreAudioVolumeGroup.getMinGainIndex())
                .isEqualTo(MUSIC_MIN_INDEX);
        expectWithMessage("Initial gain index").that(mNavCoreAudioVolumeGroup.getMinGainIndex())
                .isEqualTo(NAV_MIN_INDEX);
        expectWithMessage("Initial gain index").that(mOemCoreAudioVolumeGroup.getMinGainIndex())
                .isEqualTo(OEM_MIN_INDEX);
    }

    @Test
    public void checkIndexRange() {
        expectWithMessage("check inside range").that(mMusicCoreAudioVolumeGroup.isValidGainIndex(
                        (MUSIC_MAX_INDEX + MUSIC_MIN_INDEX) / 2))
                .isTrue();
        expectWithMessage("check outside range")
                .that(mMusicCoreAudioVolumeGroup.isValidGainIndex(MUSIC_MIN_INDEX - 1))
                .isFalse();
        expectWithMessage("check outside range")
                .that(mMusicCoreAudioVolumeGroup.isValidGainIndex(MUSIC_MAX_INDEX + 1))
                .isFalse();
    }

    @Test
    public void setAndGetIndex() {
        expectWithMessage("Initial am gain index")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache())
                .isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial am gain index")
                .that(mMusicCoreAudioVolumeGroup.getAmLastAudibleIndex())
                .isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial am gain index")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndex())
                .isEqualTo(MUSIC_AM_INIT_INDEX);

        int index = (MUSIC_MAX_INDEX + MUSIC_MIN_INDEX) / 2;
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(index);

        verify(mMockAudioManager).setVolumeIndexForAttributes(eq(MUSIC_ATTRIBUTES), eq(index),
                anyInt());
    }

    @Test
    public void muteUnmute() {
        int initialIndex = mMusicCoreAudioVolumeGroup.getCurrentGainIndex();

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();
        mMusicCoreAudioVolumeGroup.setMute(true);

        verify(() -> AudioManagerHelper.adjustVolumeGroupVolume(any(),
                eq(MUSIC_GROUP_ID), eq(AudioManager.ADJUST_MUTE), anyInt()));

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();
        expectWithMessage("Index unchanged")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(0);

        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);
        mMusicCoreAudioVolumeGroup.setMute(false);

        verify(mMockAudioManager).setVolumeIndexForAttributes(
                eq(MUSIC_ATTRIBUTES), eq(initialIndex), anyInt());
        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        expectWithMessage("Index unchanged")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(initialIndex);
    }

    @Test
    public void muteByVolumeZeroUnmute() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_MIN_INDEX);

        verify(mMockAudioManager).setVolumeIndexForAttributes(
                eq(MUSIC_ATTRIBUTES), eq(MUSIC_MIN_INDEX), anyInt());

        expectWithMessage("Initial mute state is muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();

        mMusicCoreAudioVolumeGroup.setMute(true);
        verify(() -> AudioManagerHelper.adjustVolumeGroupVolume(any(),
                eq(MUSIC_GROUP_ID), eq(AudioManager.ADJUST_MUTE), anyInt()));

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();
        expectWithMessage("Index unchanged")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(MUSIC_MIN_INDEX);

        mMusicCoreAudioVolumeGroup.setMute(false);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);

        mMusicCoreAudioVolumeGroup.setMute(false);
        verify(() -> AudioManagerHelper.adjustVolumeGroupVolume(any(),
                eq(MUSIC_GROUP_ID), eq(AudioManager.ADJUST_UNMUTE), anyInt()));

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();
        expectWithMessage("Index unchanged")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(MUSIC_MIN_INDEX);
    }

    @Test
    public void audioManagerIndexSynchronization() {
        int index = (MUSIC_MAX_INDEX + MUSIC_MIN_INDEX) / 2;
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(index);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        amIndex = MUSIC_AM_INIT_INDEX + 2;
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES)).thenReturn(amIndex);
        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);
        expectWithMessage("Index synchronized")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(amIndex);

        // Double sync is a no-op
        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("Index already synchronized").that(flags).isEqualTo(0);
        expectWithMessage("Index already synchronized")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(amIndex);
    }

    @Test
    public void audioManagerMuteStateSynchronization() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_AM_INIT_INDEX);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        // Mute event from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_AM_INIT_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);

        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();

        expectWithMessage("Mute State synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE);

        // Unmute event from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_AM_INIT_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);

        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        expectWithMessage("Mute State synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE);
    }

    @Test
    public void audioManagerMuteStateSynchronization_withIndexChange() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_AM_INIT_INDEX);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        // Mute event from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_AM_INIT_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);
        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();

        expectWithMessage("Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE);


        // Unmute event WITH index change from AM reported
        amIndex += 1;
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES)).thenReturn(amIndex);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(amIndex);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);
        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        expectWithMessage("Mute state and Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE
                        | CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);

        expectWithMessage("Index updated")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(amIndex);
        expectWithMessage("AM Index updated")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache())
                .isEqualTo(amIndex);
    }

    @Test
    public void audioManagerMuteStateWithVolumeZeroSynchronization() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_AM_INIT_INDEX);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        // Mute event at volume zero from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);
        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("Switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();

        expectWithMessage("Mute State and Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE
                        | CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);

        // Unmute event from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);

        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted()).isFalse();

        expectWithMessage("Mute State synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE);
        expectWithMessage("Index updated")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(MUSIC_MIN_INDEX);
        expectWithMessage("AM Index updated")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache())
                .isEqualTo(MUSIC_MIN_INDEX);
    }

    @Test
    public void audioManagerMutedAndUnmutedAtVolumeZeroSynchronization() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_AM_INIT_INDEX);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        // Mute event at from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_AM_INIT_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);
        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();
        expectWithMessage("Mute State and Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE);

        // Unmute (at volume zero) event from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_MIN_INDEX);
        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();
        expectWithMessage("Mute State synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE
                        | CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);
        expectWithMessage("Index updated")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(MUSIC_MIN_INDEX);
        expectWithMessage("AM Index updated")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache())
                .isEqualTo(MUSIC_MIN_INDEX);
    }

    @Test
    public void audioManagerMutedAtVolumeZeroAndUnmutedAtNonZeroSynchronization() {
        mMusicCoreAudioVolumeGroup.setCurrentGainIndex(MUSIC_AM_INIT_INDEX);

        int amIndex = mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache();
        expectWithMessage("Initial am gain index").that(amIndex).isEqualTo(MUSIC_AM_INIT_INDEX);

        expectWithMessage("Initial mute state is unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        // Mute event at volume zero from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
                .thenReturn(MUSIC_MIN_INDEX);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(true);
        int flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to muted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isTrue();

        expectWithMessage("Mute State and Index synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE
                        | CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);

        // Unmute event (with non zero index) from AM reported
        when(mMockAudioManager.getVolumeIndexForAttributes(MUSIC_ATTRIBUTES))
                .thenReturn(MUSIC_AM_INIT_INDEX + 1);
        when(mMockAudioManager.isVolumeGroupMuted(MUSIC_GROUP_ID)).thenReturn(false);
        when(mMockAudioManager.getLastAudibleVolumeForVolumeGroup(MUSIC_GROUP_ID))
               .thenReturn(MUSIC_AM_INIT_INDEX + 1);
        flags = mMusicCoreAudioVolumeGroup.onAudioVolumeGroupChanged(/* flags= */ 0);

        expectWithMessage("switched to unmuted")
                .that(mMusicCoreAudioVolumeGroup.isMuted())
                .isFalse();

        expectWithMessage("Mute State synchronized")
                .that(flags).isEqualTo(CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE
                        | CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE);

        expectWithMessage("Index updated")
                .that(mMusicCoreAudioVolumeGroup.getCurrentGainIndex())
                .isEqualTo(MUSIC_AM_INIT_INDEX + 1);
        expectWithMessage("AM Index updated")
                .that(mMusicCoreAudioVolumeGroup.getAmCurrentGainIndexFromCache())
                .isEqualTo(MUSIC_AM_INIT_INDEX + 1);
    }
}
