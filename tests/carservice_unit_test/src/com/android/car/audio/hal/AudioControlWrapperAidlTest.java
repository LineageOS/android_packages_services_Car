/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.audio.hal;

import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.os.IBinder.DeathRecipient;

import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_DUCKING;
import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_FOCUS;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.audio.policy.configuration.V7_0.AudioUsage;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.automotive.audiocontrol.DuckingInfo;
import android.hardware.automotive.audiocontrol.IAudioControl;
import android.hardware.automotive.audiocontrol.IFocusListener;
import android.media.AudioManager;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.audio.CarDuckingInfo;
import com.android.car.audio.hal.AudioControlWrapper.AudioControlDeathRecipient;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class AudioControlWrapperAidlTest extends AbstractExtendedMockitoTestCase {
    private static final float FADE_VALUE = 5;
    private static final float BALANCE_VALUE = 6;
    private static final int USAGE = USAGE_MEDIA;
    private static final String USAGE_NAME = AudioUsage.AUDIO_USAGE_MEDIA.toString();
    private static final int ZONE_ID = 2;
    private static final int FOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN;

    @Mock
    IBinder mBinder;

    @Mock
    IAudioControl mAudioControl;

    @Mock
    AudioControlDeathRecipient mDeathRecipient;

    private AudioControlWrapperAidl mAudioControlWrapperAidl;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AudioControlWrapperAidl.class);
    }

    @Before
    public void setUp() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mAudioControl);
        doReturn(mBinder).when(AudioControlWrapperAidl::getService);
        mAudioControlWrapperAidl = new AudioControlWrapperAidl(mBinder);
    }

    @Test
    public void setFadeTowardFront_succeeds() throws Exception {
        mAudioControlWrapperAidl.setFadeTowardFront(FADE_VALUE);

        verify(mAudioControl).setFadeTowardFront(FADE_VALUE);
    }

    @Test
    public void setBalanceTowardRight_succeeds() throws Exception {
        mAudioControlWrapperAidl.setBalanceTowardRight(BALANCE_VALUE);

        verify(mAudioControl).setBalanceTowardRight(BALANCE_VALUE);
    }

    @Test
    public void supportsFeature_forAudioFocus_returnsTrue() {
        assertThat(mAudioControlWrapperAidl.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_FOCUS))
                .isTrue();
    }

    @Test
    public void supportsFeature_forAudioDucking_returnsTrue() {
        assertThat(mAudioControlWrapperAidl.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_DUCKING))
                .isTrue();
    }

    @Test
    public void supportsFeature_forUnknownFeature_returnsFalse() {
        assertThat(mAudioControlWrapperAidl.supportsFeature(-1)).isFalse();
    }

    @Test
    public void registerFocusListener_succeeds() throws Exception {
        HalFocusListener mockListener = mock(HalFocusListener.class);
        mAudioControlWrapperAidl.registerFocusListener(mockListener);

        verify(mAudioControl).registerFocusListener(any(IFocusListener.class));
    }

    @Test
    public void onAudioFocusChange_succeeds() throws Exception {
        mAudioControlWrapperAidl.onAudioFocusChange(USAGE, ZONE_ID, FOCUS_GAIN);

        verify(mAudioControl).onAudioFocusChange(USAGE_NAME, ZONE_ID, FOCUS_GAIN);
    }

    @Test
    public void onDevicesToDuckChange_withNullDuckingInfo_throws() {
        assertThrows(NullPointerException.class,
                () -> mAudioControlWrapperAidl.onDevicesToDuckChange(null));
    }

    @Test
    public void onDevicesToDuckChange_callsHalWithDuckingInfo() throws Exception {
        CarDuckingInfo carDuckingInfo = new CarDuckingInfo(ZONE_ID, new ArrayList<>(),
                new ArrayList<>(), new int[0]);

        mAudioControlWrapperAidl.onDevicesToDuckChange(carDuckingInfo);

        ArgumentCaptor<DuckingInfo[]> captor = ArgumentCaptor.forClass(DuckingInfo[].class);
        verify(mAudioControl).onDevicesToDuckChange(captor.capture());
        DuckingInfo[] duckingInfos = captor.getValue();
        assertThat(duckingInfos).hasLength(1);
    }

    @Test
    public void onDevicesToDuckChange_convertsUsagesToXsdStrings() throws Exception {
        CarDuckingInfo carDuckingInfo = new CarDuckingInfo(ZONE_ID, new ArrayList<>(),
                new ArrayList<>(), new int[]{USAGE_MEDIA, USAGE_NOTIFICATION});

        mAudioControlWrapperAidl.onDevicesToDuckChange(carDuckingInfo);

        ArgumentCaptor<DuckingInfo[]> captor = ArgumentCaptor.forClass(DuckingInfo[].class);
        verify(mAudioControl).onDevicesToDuckChange(captor.capture());
        DuckingInfo duckingInfo = captor.getValue()[0];
        assertThat(duckingInfo.usagesHoldingFocus).asList()
                .containsExactly(AudioUsage.AUDIO_USAGE_MEDIA.toString(),
                        AudioUsage.AUDIO_USAGE_NOTIFICATION.toString());
    }

    @Test
    public void onDevicesToDuckChange_passesAlongAddressesToDuck() throws Exception {
        String mediaAddress = "media_bus";
        String navigationAddress = "navigation_bus";
        CarDuckingInfo carDuckingInfo = new CarDuckingInfo(ZONE_ID,
                Arrays.asList(mediaAddress, navigationAddress), new ArrayList<>(), new int[0]);

        mAudioControlWrapperAidl.onDevicesToDuckChange(carDuckingInfo);

        ArgumentCaptor<DuckingInfo[]> captor = ArgumentCaptor.forClass(DuckingInfo[].class);
        verify(mAudioControl).onDevicesToDuckChange(captor.capture());
        DuckingInfo duckingInfo = captor.getValue()[0];
        assertThat(duckingInfo.deviceAddressesToDuck).asList()
                .containsExactly(mediaAddress, navigationAddress);
    }

    @Test
    public void onDevicesToDuckChange_passesAlongAddressesToUnduck() throws Exception {
        String notificationAddress = "notification_bus";
        String callAddress = "call_address";
        CarDuckingInfo carDuckingInfo = new CarDuckingInfo(ZONE_ID, new ArrayList<>(),
                Arrays.asList(notificationAddress, callAddress), new int[0]);

        mAudioControlWrapperAidl.onDevicesToDuckChange(carDuckingInfo);

        ArgumentCaptor<DuckingInfo[]> captor = ArgumentCaptor.forClass(DuckingInfo[].class);
        verify(mAudioControl).onDevicesToDuckChange(captor.capture());
        DuckingInfo duckingInfo = captor.getValue()[0];
        assertThat(duckingInfo.deviceAddressesToUnduck).asList()
                .containsExactly(notificationAddress, callAddress);
    }

    @Test
    public void onDevicesToDuckChange_passesAlongZoneId() throws Exception {
        CarDuckingInfo carDuckingInfo = new CarDuckingInfo(ZONE_ID, new ArrayList<>(),
                new ArrayList<>(), new int[0]);

        mAudioControlWrapperAidl.onDevicesToDuckChange(carDuckingInfo);

        ArgumentCaptor<DuckingInfo[]> captor = ArgumentCaptor.forClass(DuckingInfo[].class);
        verify(mAudioControl).onDevicesToDuckChange(captor.capture());
        DuckingInfo duckingInfo = captor.getValue()[0];
        assertThat(duckingInfo.zoneId).isEqualTo(ZONE_ID);
    }

    @Test
    public void linkToDeath_callsBinder() throws Exception {
        mAudioControlWrapperAidl.linkToDeath(null);

        verify(mBinder).linkToDeath(any(DeathRecipient.class), eq(0));
    }

    @Test
    public void binderDied_fetchesNewBinder() throws Exception {
        mAudioControlWrapperAidl.linkToDeath(null);

        ArgumentCaptor<DeathRecipient> captor = ArgumentCaptor.forClass(DeathRecipient.class);
        verify(mBinder).linkToDeath(captor.capture(), eq(0));
        IBinder.DeathRecipient deathRecipient = captor.getValue();

        deathRecipient.binderDied();

        ExtendedMockito.verify(() -> AudioControlWrapperAidl.getService());
    }

    @Test
    public void binderDied_relinksToDeath() throws Exception {
        mAudioControlWrapperAidl.linkToDeath(null);

        ArgumentCaptor<DeathRecipient> captor = ArgumentCaptor.forClass(DeathRecipient.class);
        verify(mBinder).linkToDeath(captor.capture(), eq(0));
        IBinder.DeathRecipient deathRecipient = captor.getValue();

        deathRecipient.binderDied();

        verify(mBinder, times(2)).linkToDeath(any(DeathRecipient.class), eq(0));
    }

    @Test
    public void binderDied_callsDeathRecipient() throws Exception {
        mAudioControlWrapperAidl.linkToDeath(mDeathRecipient);

        ArgumentCaptor<DeathRecipient> captor = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(mBinder).linkToDeath(captor.capture(), eq(0));
        IBinder.DeathRecipient deathRecipient = captor.getValue();

        deathRecipient.binderDied();

        verify(mDeathRecipient).serviceDied();
    }
}
