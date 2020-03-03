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
package com.android.car.audio;

import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_LOSS;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioAttributes.AttributeUsage;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class CarAudioFocusUnitTest {
    private static final int CLIENT_UID = 1;
    private static final String FIRST_CLIENT_ID = "first-client-id";
    private static final String SECOND_CLIENT_ID = "second-client-id";
    private static final String PACKAGE_NAME = "com.android.car.audio";
    private static final int AUDIOFOCUS_FLAG = 0;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    @Mock
    private AudioManager mMockAudioManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private AudioPolicy mAudioPolicy;

    private CarAudioFocus mCarAudioFocus;

    @Before
    public void setUp() {
        mCarAudioFocus = new CarAudioFocus(mMockAudioManager, mMockPackageManager);
        mCarAudioFocus.setOwningPolicy(mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_withNoCurrentFocusHolder_requestGranted() {
        AudioFocusInfo audioFocusInfo = getInfoForFirstClientWithMedia();
        mCarAudioFocus.onAudioFocusRequest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(audioFocusInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_withSameClientIdSameUsage_requestGranted() {
        AudioFocusInfo audioFocusInfo = getInfoForFirstClientWithMedia();
        mCarAudioFocus.onAudioFocusRequest(audioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        AudioFocusInfo sameClientAndUsageFocusInfo = getInfoForFirstClientWithMedia();
        mCarAudioFocus.onAudioFocusRequest(sameClientAndUsageFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(2)).setFocusRequestResult(sameClientAndUsageFocusInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_withSameClientIdDifferentUsage_requestFailed() {
        requestFocusForMediaWithFirstClient();

        AudioFocusInfo sameClientFocusInfo = getInfo(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                FIRST_CLIENT_ID, AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(sameClientFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(sameClientFocusInfo,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_concurrentRequest_requestGranted() {
        requestFocusForMediaWithFirstClient();

        AudioFocusInfo concurrentFocusInfo = getConcurrentInfo(AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(concurrentFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(concurrentFocusInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_concurrentRequestWithoutDucking_holderLosesFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo concurrentFocusInfo = getConcurrentInfo(AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(concurrentFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).dispatchAudioFocusChange(initialFocusInfo,
                AudioManager.AUDIOFOCUS_LOSS, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_concurrentRequestMayDuck_holderRetainsFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo concurrentFocusInfo = getConcurrentInfo(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        mCarAudioFocus.onAudioFocusRequest(concurrentFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(0)).dispatchAudioFocusChange(eq(initialFocusInfo),
                anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_exclusiveRequest_requestGranted() {
        requestFocusForMediaWithFirstClient();

        AudioFocusInfo exclusiveRequestInfo = getExclusiveInfo(AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(exclusiveRequestInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(exclusiveRequestInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_exclusiveRequest_holderLosesFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo exclusiveRequestInfo = getExclusiveInfo(AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(exclusiveRequestInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).dispatchAudioFocusChange(initialFocusInfo,
                AudioManager.AUDIOFOCUS_LOSS, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_exclusiveRequestMayDuck_holderLosesFocusTransiently() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo exclusiveRequestInfo = getExclusiveInfo(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        mCarAudioFocus.onAudioFocusRequest(exclusiveRequestInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).dispatchAudioFocusChange(initialFocusInfo,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, mAudioPolicy);
    }

    @Test
    public void onAudioFocus_rejectRequest_requestFailed() {
        requestFocusForUsageWithFirstClient(USAGE_ASSISTANT);

        AudioFocusInfo rejectRequestInfo = getRejectInfo();
        mCarAudioFocus.onAudioFocusRequest(rejectRequestInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(rejectRequestInfo,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    @Test
    public void onAudioFocus_rejectRequest_holderRetainsFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForUsageWithFirstClient(USAGE_ASSISTANT);

        AudioFocusInfo rejectRequestInfo = getRejectInfo();
        mCarAudioFocus.onAudioFocusRequest(rejectRequestInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(0)).dispatchAudioFocusChange(eq(initialFocusInfo),
                anyInt(), eq(mAudioPolicy));
    }

    // System Usage tests

    @Test
    public void onAudioFocus_exclusiveWithSystemUsage_requestGranted() {
        requestFocusForMediaWithFirstClient();

        AudioFocusInfo exclusiveSystemUsageInfo = getExclusiveWithSystemUsageInfo();
        mCarAudioFocus.onAudioFocusRequest(exclusiveSystemUsageInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(exclusiveSystemUsageInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocus_exclusiveWithSystemUsage_holderLosesFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo exclusiveSystemUsageInfo = getExclusiveWithSystemUsageInfo();
        mCarAudioFocus.onAudioFocusRequest(exclusiveSystemUsageInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).dispatchAudioFocusChange(initialFocusInfo,
                AUDIOFOCUS_LOSS, mAudioPolicy);
    }

    @Test
    public void onAudioFocus_concurrentWithSystemUsage_requestGranted() {
        requestFocusForMediaWithFirstClient();

        AudioFocusInfo concurrentSystemUsageInfo = getConcurrentWithSystemUsageInfo();
        mCarAudioFocus.onAudioFocusRequest(concurrentSystemUsageInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(concurrentSystemUsageInfo,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    @Test
    public void onAudioFocus_concurrentWithSystemUsageAndConcurrent_holderRetainsFocus() {
        AudioFocusInfo initialFocusInfo = requestFocusForMediaWithFirstClient();

        AudioFocusInfo concurrentSystemUsageInfo = getConcurrentWithSystemUsageInfo();
        mCarAudioFocus.onAudioFocusRequest(concurrentSystemUsageInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(0)).dispatchAudioFocusChange(eq(initialFocusInfo),
                anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocus_rejectWithSystemUsage_requestFailed() {
        requestFocusForUsageWithFirstClient(USAGE_VOICE_COMMUNICATION);

        AudioFocusInfo rejectWithSystemUsageInfo = getRejectWithSystemUsageInfo();
        mCarAudioFocus.onAudioFocusRequest(rejectWithSystemUsageInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager, times(1)).setFocusRequestResult(rejectWithSystemUsageInfo,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE is concurrent with USAGE_MEDIA
    private AudioFocusInfo getConcurrentInfo(int gainType) {
        return getInfo(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, SECOND_CLIENT_ID, gainType);
    }

    // USAGE_VEHICLE_STATUS is concurrent with USAGE_MEDIA
    private AudioFocusInfo getConcurrentWithSystemUsageInfo() {
        return getSystemUsageInfo(USAGE_VEHICLE_STATUS, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
    }

    // USAGE_MEDIA is exclusive with USAGE_MEDIA
    private AudioFocusInfo getExclusiveInfo(int gainType) {
        return getInfo(USAGE_MEDIA, SECOND_CLIENT_ID, gainType);
    }

    // USAGE_EMERGENCY is exclusive with USAGE_MEDIA
    private AudioFocusInfo getExclusiveWithSystemUsageInfo() {
        return getSystemUsageInfo(USAGE_EMERGENCY, AUDIOFOCUS_GAIN);
    }

    // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE is rejected with USAGE_ASSISTANT
    private AudioFocusInfo getRejectInfo() {
        return getInfo(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, SECOND_CLIENT_ID,
                AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
    }

    // USAGE_ANNOUNCEMENT is rejected with USAGE_VOICE_COMMUNICATION
    private AudioFocusInfo getRejectWithSystemUsageInfo() {
        return getSystemUsageInfo(USAGE_ANNOUNCEMENT, AUDIOFOCUS_GAIN);
    }

    private AudioFocusInfo requestFocusForUsageWithFirstClient(@AttributeUsage int usage) {
        AudioFocusInfo initialFocusInfo = getInfo(usage, FIRST_CLIENT_ID, AUDIOFOCUS_GAIN);
        mCarAudioFocus.onAudioFocusRequest(initialFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);
        return initialFocusInfo;
    }

    private AudioFocusInfo requestFocusForMediaWithFirstClient() {
        return requestFocusForUsageWithFirstClient(USAGE_MEDIA);
    }

    private AudioFocusInfo getInfoForFirstClientWithMedia() {
        return getInfo(USAGE_MEDIA, FIRST_CLIENT_ID, AUDIOFOCUS_GAIN);
    }

    private AudioFocusInfo getInfo(@AttributeUsage int usage, String clientId, int gainType) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(usage)
                .build();
        return getInfo(audioAttributes, clientId, gainType);
    }

    private AudioFocusInfo getSystemUsageInfo(@AttributeUsage int systemUsage, int gainType) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setSystemUsage(systemUsage)
                .build();
        return getInfo(audioAttributes, SECOND_CLIENT_ID, gainType);
    }

    private AudioFocusInfo getInfo(AudioAttributes audioAttributes, String clientId, int gainType) {
        return new AudioFocusInfo(audioAttributes, CLIENT_UID, clientId, PACKAGE_NAME,
                gainType, AudioManager.AUDIOFOCUS_NONE,
                AUDIOFOCUS_FLAG, Build.VERSION.SDK_INT);
    }
}
