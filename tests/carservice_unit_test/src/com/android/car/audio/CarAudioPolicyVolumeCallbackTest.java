/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_MUTE;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.ADJUST_SAME;
import static android.media.AudioManager.ADJUST_TOGGLE_MUTE;
import static android.media.AudioManager.ADJUST_UNMUTE;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_SHOW_UI;

import static com.android.car.audio.CarAudioContext.VOICE_COMMAND;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy.Builder;

import com.android.car.audio.CarAudioPolicyVolumeCallback.AudioPolicyVolumeCallbackInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioPolicyVolumeCallbackTest {

    private static final int TEST_VOLUME_GROUP = 0;
    private static final int TEST_VOLUME = 5;
    private static final int TEST_MIN_VOLUME = 0;
    private static final int TEST_MAX_VOLUME = 10;

    private static final int TEST_EXPECTED_FLAGS = FLAG_FROM_KEY | FLAG_SHOW_UI;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    @Mock
    private CarVolumeInfoWrapper mMockVolumeInfoWrapper;
    @Mock
    AudioManager mMockAudioManager;
    @Mock
    Builder mMockBuilder;

    @Mock
    private AudioPolicyVolumeCallbackInternal mVolumeCallbackInternal;

    private CarAudioPolicyVolumeCallback mCarAudioPolicyVolumeCallback;

    @Before
    public void setUp() {
        mCarAudioPolicyVolumeCallback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, false);
        when(mMockVolumeInfoWrapper.getSuggestedAudioContextForPrimaryZone())
                .thenReturn(VOICE_COMMAND);
        when(mMockVolumeInfoWrapper.getVolumeGroupIdForAudioContext(anyInt(), anyInt()))
                .thenReturn(TEST_VOLUME_GROUP);
        when(mMockVolumeInfoWrapper.getGroupMaxVolume(anyInt(), anyInt()))
                .thenReturn(TEST_MAX_VOLUME);
        when(mMockVolumeInfoWrapper.getGroupMinVolume(anyInt(), anyInt()))
                .thenReturn(TEST_MIN_VOLUME);
    }

    @Test
    public void addVolumeCallbackToPolicy_withNullPolicyBuilder_fails() {
        assertThrows(NullPointerException.class, () ->
                CarAudioPolicyVolumeCallback.addVolumeCallbackToPolicy(
                        null, mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, false));
    }

    @Test
    public void addVolumeCallbackToPolicy_withNullCarAudioService_fails() {
        assertThrows(NullPointerException.class, () ->
                CarAudioPolicyVolumeCallback.addVolumeCallbackToPolicy(mMockBuilder,
                        null, mMockAudioManager, mMockVolumeInfoWrapper,
                        false));
    }

    @Test
    public void addVolumeCallbackToPolicy_withNullAudioManager_fails() {
        assertThrows(NullPointerException.class, () ->
                CarAudioPolicyVolumeCallback.addVolumeCallbackToPolicy(mMockBuilder,
                        mVolumeCallbackInternal, null, mMockVolumeInfoWrapper,
                        false));
    }


    @Test
    public void addVolumeCallbackToPolicy_registersVolumePolicy() {
        CarAudioPolicyVolumeCallback.addVolumeCallbackToPolicy(mMockBuilder,
                mVolumeCallbackInternal, mMockAudioManager, mMockVolumeInfoWrapper,
                false);

        verify(mMockBuilder).setAudioPolicyVolumeCallback(any());
    }

    @Test
    public void onVolumeAdjustment_withAdjustRaise_increasesGroupVolume() {
        setGroupVolume(TEST_VOLUME);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_RAISE);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_VOLUME + 1, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustLower_decreasesGroupVolume() {
        setGroupVolume(TEST_VOLUME);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_LOWER);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_VOLUME - 1, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustLower_atMinVolume_setsGroupVolumeToMin() {
        setGroupVolume(TEST_MIN_VOLUME);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_LOWER);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_MIN_VOLUME, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustRaise_atMaxVolume_setsGroupVolumeToMax() {
        setGroupVolume(TEST_MAX_VOLUME);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_RAISE);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_MAX_VOLUME, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustRaise_whileMuted_setsGroupVolumeToMin() {
        setGroupVolume(TEST_MAX_VOLUME);
        setGroupVolumeMute(true);

        CarAudioPolicyVolumeCallback callback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, true);


        callback.onVolumeAdjustment(ADJUST_RAISE);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_MIN_VOLUME, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustLower_whileMuted_setsGroupVolumeToMin() {
        setGroupVolume(TEST_MAX_VOLUME);
        setGroupVolumeMute(true);

        CarAudioPolicyVolumeCallback callback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, true);

        callback.onVolumeAdjustment(ADJUST_LOWER);

        verify(mVolumeCallbackInternal).onGroupVolumeChange(PRIMARY_AUDIO_ZONE,
                TEST_VOLUME_GROUP, TEST_MIN_VOLUME, TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustSame_doesNothing() {
        setGroupVolume(TEST_VOLUME);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_SAME);

        verify(mVolumeCallbackInternal, never())
                .onGroupVolumeChange(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void onVolumeAdjustment_withAdjustMute_mutesMasterVolume() {
        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_MUTE);

        verify(mVolumeCallbackInternal).onMuteChange(true, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withAdjustUnMute_unMutesMasterVolume() {
        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_UNMUTE);

        verify(mVolumeCallbackInternal).onMuteChange(false, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withToggleMute_whileMuted_unMutesMasterVolume() {
        when(mMockAudioManager.isMasterMute()).thenReturn(true);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_TOGGLE_MUTE);

        verify(mVolumeCallbackInternal).onMuteChange(false, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_withToggleMute_whileUnMuted_mutesMasterVolume() {
        when(mMockAudioManager.isMasterMute()).thenReturn(false);

        mCarAudioPolicyVolumeCallback.onVolumeAdjustment(ADJUST_TOGGLE_MUTE);

        verify(mVolumeCallbackInternal).onMuteChange(true, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_forGroupMute_withAdjustMute_mutesVolumeGroup() {
        CarAudioPolicyVolumeCallback callback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, true);

        callback.onVolumeAdjustment(ADJUST_MUTE);

        verify(mVolumeCallbackInternal).onMuteChange(true, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_forGroupMute_withAdjustToggleMute_togglesMutesVolumeGroup() {
        setGroupVolumeMute(true);

        CarAudioPolicyVolumeCallback callback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, true);

        callback.onVolumeAdjustment(ADJUST_TOGGLE_MUTE);

        verify(mVolumeCallbackInternal).onMuteChange(false, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    @Test
    public void onVolumeAdjustment_forGroupMute_withAdjustUnMute_UnMutesVolumeGroup() {
        setGroupVolumeMute(false);

        CarAudioPolicyVolumeCallback callback =
                new CarAudioPolicyVolumeCallback(mVolumeCallbackInternal, mMockAudioManager,
                        mMockVolumeInfoWrapper, true);

        callback.onVolumeAdjustment(ADJUST_UNMUTE);

        verify(mVolumeCallbackInternal).onMuteChange(false, PRIMARY_AUDIO_ZONE, TEST_VOLUME_GROUP,
                TEST_EXPECTED_FLAGS);
    }

    private void setGroupVolume(int groupVolume) {
        when(mMockVolumeInfoWrapper.getGroupVolume(anyInt(), anyInt()))
                .thenReturn(groupVolume);
    }

    private void setGroupVolumeMute(boolean mute) {
        when(mMockVolumeInfoWrapper.isVolumeGroupMuted(anyInt(), anyInt()))
                .thenReturn(mute);
    }
}
