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

import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.car.builtin.media.AudioManagerHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioManager.AudioServerStateCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.FadeManagerConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.Handler;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.CarLog;

import com.google.common.util.concurrent.MoreExecutors;

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
    public void setMasterMute() {
        boolean mute = true;
        int flags = FLAG_SHOW_UI;

        mAudioManagerWrapper.setMasterMute(mute, flags);

        verify(mAudioManager).setMasterMute(mute, flags);
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

    @Test
    public void isAudioServerRunning() {
        when(mAudioManager.isAudioServerRunning()).thenReturn(true);

        expectWithMessage("Audio server state").that(mAudioManagerWrapper.isAudioServerRunning())
                .isTrue();
    }

    @Test
    public void setSupportedSystemUsages() {
        int[] systemUsages = new int[]{USAGE_EMERGENCY, USAGE_SAFETY};

        mAudioManagerWrapper.setSupportedSystemUsages(systemUsages);

        verify(mAudioManager).setSupportedSystemUsages(systemUsages);
    }

    @Test
    public void registerAudioDeviceCallback() {
        AudioDeviceCallback callback = Mockito.mock(AudioDeviceCallback.class);
        Handler handler = Mockito.mock(Handler.class);

        mAudioManagerWrapper.registerAudioDeviceCallback(callback, handler);

        verify(mAudioManager).registerAudioDeviceCallback(callback, handler);
    }

    @Test
    public void unregisterAudioDeviceCallback() {
        AudioDeviceCallback callback = Mockito.mock(AudioDeviceCallback.class);

        mAudioManagerWrapper.unregisterAudioDeviceCallback(callback);

        verify(mAudioManager).unregisterAudioDeviceCallback(callback);
    }

    @Test
    public void setAudioServerStateCallback() {
        AudioServerStateCallback callback = Mockito.mock(AudioServerStateCallback.class);
        Executor executor = MoreExecutors.directExecutor();

        mAudioManagerWrapper.setAudioServerStateCallback(executor, callback);

        verify(mAudioManager).setAudioServerStateCallback(executor, callback);
    }

    @Test
    public void clearAudioServerStateCallback() {
        mAudioManagerWrapper.clearAudioServerStateCallback();

        verify(mAudioManager).clearAudioServerStateCallback();
    }

    @Test
    public void registerAudioPolicy() {
        AudioPolicy policy = Mockito.mock(AudioPolicy.class);

        mAudioManagerWrapper.registerAudioPolicy(policy);

        verify(mAudioManager).registerAudioPolicy(policy);
    }

    @Test
    public void unregisterAudioPolicy() {
        AudioPolicy policy = Mockito.mock(AudioPolicy.class);

        mAudioManagerWrapper.unregisterAudioPolicy(policy);

        verify(mAudioManager).unregisterAudioPolicy(policy);
    }

    @Test
    public void unregisterAudioPolicyAsync() {
        AudioPolicy policy = Mockito.mock(AudioPolicy.class);

        mAudioManagerWrapper.unregisterAudioPolicyAsync(policy);

        verify(mAudioManager).unregisterAudioPolicyAsync(policy);
    }

    @Test
    public void setStreamVolume() {
        int stream = AudioManager.STREAM_ALARM;
        int index = 100;
        int flags = FLAG_FROM_KEY;

        mAudioManagerWrapper.setStreamVolume(stream, index, flags);

        verify(mAudioManager).setStreamVolume(stream, index, flags);
    }

    @Test
    public void getStreamMaxVolume() {
        int stream = AudioManager.STREAM_RING;
        int index = 9001;
        when(mAudioManager.getStreamMaxVolume(stream)).thenReturn(index);

        expectWithMessage("Max ring stream volume").that(
                mAudioManagerWrapper.getStreamMaxVolume(stream)).isEqualTo(index);
    }

    @Test
    public void getStreamMinVolume() {
        int stream = AudioManager.STREAM_SYSTEM;
        int index = 1;
        when(mAudioManager.getStreamMinVolume(stream)).thenReturn(index);

        expectWithMessage("Min system stream volume").that(
                mAudioManagerWrapper.getStreamMinVolume(stream)).isEqualTo(index);
    }

    @Test
    public void getStreamVolume() {
        int stream = AudioManager.STREAM_MUSIC;
        int index = 8675309;
        when(mAudioManager.getStreamVolume(stream)).thenReturn(index);

        expectWithMessage("Min music stream volume").that(
                mAudioManagerWrapper.getStreamVolume(stream)).isEqualTo(index);
    }

    @Test
    public void setParameter() {
        String parameter = "test_parameter:key";
        mAudioManagerWrapper.setParameters(parameter);

        verify(mAudioManager).setParameters(parameter);
    }

    @Test
    public void getDevices() {
        AudioDeviceInfo info1 = Mockito.mock(AudioDeviceInfo.class);
        AudioDeviceInfo info2 = Mockito.mock(AudioDeviceInfo.class);
        AudioDeviceInfo[] infos = new AudioDeviceInfo[]{info1, info2};
        when(mAudioManager.getDevices(GET_DEVICES_OUTPUTS)).thenReturn(infos);

        expectWithMessage("Output audio devices").that(
                mAudioManagerWrapper.getDevices(GET_DEVICES_OUTPUTS)).isEqualTo(infos);
    }

    @Test
    public void registerAudioPlaybackCallback() {
        AudioPlaybackCallback callback = Mockito.mock(AudioPlaybackCallback.class);
        Handler handler = Mockito.mock(Handler.class);

        mAudioManagerWrapper.registerAudioPlaybackCallback(callback, handler);

        verify(mAudioManager).registerAudioPlaybackCallback(callback, handler);
    }

    @Test
    public void unregisterAudioPlaybackCallback() {
        AudioPlaybackCallback callback = Mockito.mock(AudioPlaybackCallback.class);

        mAudioManagerWrapper.unregisterAudioPlaybackCallback(callback);

        verify(mAudioManager).unregisterAudioPlaybackCallback(callback);
    }

    @Test
    public void getActivePlaybackConfigurations() {
        AudioPlaybackConfiguration config1 = Mockito.mock(AudioPlaybackConfiguration.class);
        AudioPlaybackConfiguration config2 = Mockito.mock(AudioPlaybackConfiguration.class);
        List<AudioPlaybackConfiguration> configs = List.of(config1, config2);
        when(mAudioManager.getActivePlaybackConfigurations()).thenReturn(configs);

        List<AudioPlaybackConfiguration> activeConfigs =
                mAudioManagerWrapper.getActivePlaybackConfigurations();

        expectWithMessage("Active playback configs").that(activeConfigs)
                .containsExactlyElementsIn(configs);
    }

    @Test
    public void releaseAudioPatch() {
        AudioManagerHelper.AudioPatchInfo info =
                new AudioManagerHelper.AudioPatchInfo(/* sourceAddress= */ "input",
                /* sinkAddress= */ "output", /* handleId= */ 10);
        doReturn(true).when(() -> AudioManagerHelper.releaseAudioPatch(mAudioManager, info));

        expectWithMessage("Release patch state").that(mAudioManagerWrapper.releaseAudioPatch(info))
                .isTrue();
    }

    @Test
    public void getAudioVolumeGroups() {
        List<AudioVolumeGroup> groups = CoreAudioRoutingUtils.getVolumeGroups();
        doReturn(groups).when(AudioManager::getAudioVolumeGroups);

        expectWithMessage("Core volume groups")
                .that(AudioManagerWrapper.getAudioVolumeGroups()).containsExactlyElementsIn(groups);
    }

    @Test
    public void getAudioProductStrategies() {
        List<AudioProductStrategy> strategies = CoreAudioRoutingUtils.getProductStrategies();
        doReturn(strategies).when(AudioManager::getAudioProductStrategies);

        expectWithMessage("Audio product strategies")
                .that(AudioManagerWrapper.getAudioProductStrategies())
                .containsExactlyElementsIn(strategies);
    }
}
