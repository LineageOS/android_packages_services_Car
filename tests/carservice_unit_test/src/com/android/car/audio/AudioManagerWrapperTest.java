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

import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.car.builtin.media.AudioManagerHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.AbstractExtendedMockitoTestCase.CustomMockitoSessionBuilder;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.FadeManagerConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioProductStrategy;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.CarLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public final class AudioManagerWrapperTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarLog.TAG_AUDIO;
    private static final int TEST_GROUP_ID = 4;
    private static final int TEST_MIN_VOLUME_INDEX = 1;
    private static final int TEST_MAX_VOLUME_INDEX = 9001;
    private static final int TEST_VOLUME_INDEX = 8675309;
    private static final boolean TEST_MUTED_STATE = true;
    private static final int TEST_SET_INDEX = 13;
    private static final int TEST_FLAGS = 0;
    private static final String TEST_ADDRESS = "bus_to_nowhere";
    private static final int TEST_GAIN = -640;
    private static final boolean TEST_IS_OUTPUT = true;
    private static final boolean TEST_GAIN_SET_STATE = true;

    @Mock
    private AudioManager mAudioManager;
    @Mock
    private AudioProductStrategy mTestStrategy;
    @Mock
    private AudioDeviceAttributes mTestDeviceAttributes;
    @Mock
    private AudioPolicy mAudioPolicy;

    private AudioManagerWrapper mAudioManagerWrapper;
    private AudioAttributes mTestAudioAttributes;

    public AudioManagerWrapperTest() {
        super(AudioManagerWrapperTest.TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AudioManager.class)
                .spyStatic(AudioManagerHelper.class);
    }

    @Before
    public void setUp() throws Exception {
        mTestAudioAttributes = CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
        when(mAudioManager.getMinVolumeIndexForAttributes(mTestAudioAttributes))
                .thenReturn(TEST_MIN_VOLUME_INDEX);
        when(mAudioManager.getMaxVolumeIndexForAttributes(mTestAudioAttributes))
                .thenReturn(TEST_MAX_VOLUME_INDEX);
        when(mAudioManager.getVolumeIndexForAttributes(mTestAudioAttributes))
                .thenReturn(TEST_VOLUME_INDEX);
        when(mAudioManager.getLastAudibleVolumeForVolumeGroup(TEST_GROUP_ID))
                .thenReturn(TEST_VOLUME_INDEX);
        doReturn(TEST_MUTED_STATE)
                .when(() -> AudioManagerHelper.isVolumeGroupMuted(eq(mAudioManager),
                        eq(TEST_GROUP_ID)));
        doReturn(TEST_GAIN_SET_STATE).when(() -> AudioManagerHelper
                .setAudioDeviceGain(eq(mAudioManager), eq(TEST_ADDRESS),
                        eq(TEST_GAIN), eq(TEST_IS_OUTPUT)));
        doReturn(TEST_MUTED_STATE)
                .when(() -> AudioManagerHelper.isMasterMute(eq(mAudioManager)));

        mAudioManagerWrapper = new AudioManagerWrapper(mAudioManager);
    }

    @Test
    public void getMinVolumeIndexForAttributes() {
        expectWithMessage("Min volume index")
                .that(mAudioManagerWrapper.getMinVolumeIndexForAttributes(mTestAudioAttributes))
                .isEqualTo(TEST_MIN_VOLUME_INDEX);
    }

    @Test
    public void getMaxVolumeIndexForAttributes() {
        expectWithMessage("Max volume index")
                .that(mAudioManagerWrapper.getMaxVolumeIndexForAttributes(mTestAudioAttributes))
                .isEqualTo(TEST_MAX_VOLUME_INDEX);
    }

    @Test
    public void getVolumeIndexForAttributes() {
        expectWithMessage("Volume index")
                .that(mAudioManagerWrapper.getVolumeIndexForAttributes(mTestAudioAttributes))
                .isEqualTo(TEST_VOLUME_INDEX);
    }

    @Test
    public void isVolumeGroupMuted() {
        expectWithMessage("Volume group muted state")
                .that(mAudioManagerWrapper.isVolumeGroupMuted(TEST_GROUP_ID))
                .isTrue();
    }

    @Test
    public void getLastAudibleVolumeForVolumeGroup() {
        expectWithMessage("Last audible volume")
                .that(mAudioManagerWrapper.getLastAudibleVolumeForVolumeGroup(TEST_GROUP_ID))
                .isEqualTo(TEST_VOLUME_INDEX);
    }

    @Test
    public void setVolumeGroupVolumeIndex() {
        mAudioManagerWrapper.setVolumeGroupVolumeIndex(TEST_GROUP_ID, TEST_SET_INDEX, TEST_FLAGS);

        verify(mAudioManager).setVolumeGroupVolumeIndex(TEST_GROUP_ID, TEST_SET_INDEX, TEST_FLAGS);
    }

    @Test
    public void adjustVolumeGroupVolume() {
        mAudioManagerWrapper.adjustVolumeGroupVolume(TEST_GROUP_ID, ADJUST_LOWER, TEST_FLAGS);

        verify(mAudioManager).adjustVolumeGroupVolume(TEST_GROUP_ID, ADJUST_LOWER, TEST_FLAGS);
    }

    @Test
    public void setPreferredDeviceForStrategy() {
        mAudioManagerWrapper.setPreferredDeviceForStrategy(mTestStrategy, mTestDeviceAttributes);

        verify(mAudioManager).setPreferredDeviceForStrategy(mTestStrategy, mTestDeviceAttributes);
    }

    @Test
    public void setAudioDeviceGain() {
        expectWithMessage("Volume gain set state")
                .that(mAudioManagerWrapper
                        .setAudioDeviceGain(TEST_ADDRESS, TEST_GAIN, TEST_IS_OUTPUT));
    }

    @Test
    public void requestAudioFocus() {
        AudioFocusRequest request = Mockito.mock(AudioFocusRequest.class);

        mAudioManagerWrapper.requestAudioFocus(request);

        verify(mAudioManager).requestAudioFocus(request);
    }

    @Test
    public void abandonAudioFocusRequest() {
        AudioFocusRequest request = Mockito.mock(AudioFocusRequest.class);

        mAudioManagerWrapper.abandonAudioFocusRequest(request);

        verify(mAudioManager).abandonAudioFocusRequest(request);
    }

    @Test
    public void isMasterMuted() {
        expectWithMessage("Master mute state").that(mAudioManagerWrapper.isMasterMuted())
                .isEqualTo(TEST_MUTED_STATE);
    }

    @Test
    public void setFocusRequestResult() {
        AudioFocusInfo info = Mockito.mock(AudioFocusInfo.class);

        mAudioManagerWrapper.setFocusRequestResult(info, AUDIOFOCUS_GAIN, mAudioPolicy);

        verify(mAudioManager).setFocusRequestResult(info, AUDIOFOCUS_GAIN, mAudioPolicy);
    }

    @Test
    public void dispatchAudioFocusChange() {
        AudioFocusInfo info = Mockito.mock(AudioFocusInfo.class);
        when(mAudioManagerWrapper.dispatchAudioFocusChange(any(), anyInt(), any()))
                .thenReturn(AUDIOFOCUS_REQUEST_GRANTED);

        int results = mAudioManagerWrapper.dispatchAudioFocusChange(info, AUDIOFOCUS_GAIN,
                mAudioPolicy);

        expectWithMessage("Audio focus dispatch results").that(results)
                .isEqualTo(AUDIOFOCUS_REQUEST_GRANTED);
        verify(mAudioManager).dispatchAudioFocusChange(info, AUDIOFOCUS_GAIN, mAudioPolicy);
    }

    @Test
    public void dispatchAudioFocusChangeWithFade() {
        AudioFocusInfo info = Mockito.mock(AudioFocusInfo.class);
        List<AudioFocusInfo> activeInfos = List.of();
        FadeManagerConfiguration config = Mockito.mock(FadeManagerConfiguration.class);
        when(mAudioManagerWrapper.dispatchAudioFocusChangeWithFade(any(),
                anyInt(), any(), any(), any())).thenReturn(AUDIOFOCUS_REQUEST_GRANTED);

        int results = mAudioManagerWrapper.dispatchAudioFocusChangeWithFade(info, AUDIOFOCUS_GAIN,
                mAudioPolicy, activeInfos, config);

        expectWithMessage("Audio focus with fade dispatch results").that(results)
                .isEqualTo(AUDIOFOCUS_REQUEST_GRANTED);
        verify(mAudioManager).dispatchAudioFocusChangeWithFade(info, AUDIOFOCUS_GAIN, mAudioPolicy,
                activeInfos, config);
    }

    @Test
    public void registerVolumeGroupCallback() {
        CoreAudioVolumeGroupCallback callback = Mockito.mock(CoreAudioVolumeGroupCallback.class);
        Executor executor = Mockito.mock(Executor.class);

        mAudioManagerWrapper.registerVolumeGroupCallback(executor, callback);

        verify(mAudioManager).registerVolumeGroupCallback(executor, callback);
    }

    @Test
    public void unregisterVolumeGroupCallback() {
        CoreAudioVolumeGroupCallback callback = Mockito.mock(CoreAudioVolumeGroupCallback.class);

        mAudioManagerWrapper.unregisterVolumeGroupCallback(callback);

        verify(mAudioManager).unregisterVolumeGroupCallback(callback);
    }
}