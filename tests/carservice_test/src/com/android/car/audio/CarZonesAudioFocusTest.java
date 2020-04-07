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

import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.os.Build;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class CarZonesAudioFocusTest {
    private static final String MEDIA_CLIENT_ID = "media-client-id";
    private static final String NAVIGATION_CLIENT_ID = "nav-client-id";
    private static final String CALL_CLIENT_ID = "call-client-id";
    private static final String PACKAGE_NAME = "com.android.car.audio";
    private static final int AUDIOFOCUS_FLAG = 0;
    private static final int PRIMARY_ZONE_ID = CarAudioManager.PRIMARY_AUDIO_ZONE;
    private static final int SECONDARY_ZONE_ID = CarAudioManager.PRIMARY_AUDIO_ZONE + 1;
    private static final int MEDIA_CLIENT_UID_1 = 1086753;
    private static final int MEDIA_CLIENT_UID_2 = 1000009;
    private static final int NAVIGATION_CLIENT_UID = 1010101;
    private static final int TEST_USER_ID = 10;
    private static final int CALL_CLIENT_UID = 1086753;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    @Mock
    private AudioManager mMockAudioManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private AudioPolicy mAudioPolicy;
    @Mock
    private CarAudioZone mPrimaryAudioZone;
    @Mock
    private CarAudioZone mSecondaryAudioZone;
    @Mock
    private CarAudioService mCarAudioService;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private CarAudioSettings mCarAudioSettings;
    @Mock
    private CarZonesAudioFocus mCarZonesAudioFocus;

    private CarAudioZone[] mMockAudioZones;

    @Before
    public void setUp() {
        mMockAudioZones = generateAudioZones();
        mCarZonesAudioFocus =
                new CarZonesAudioFocus(mMockAudioManager, mMockPackageManager,
                        mMockAudioZones, mCarAudioSettings);
        mCarZonesAudioFocus.setOwningPolicy(mCarAudioService, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_withNoCurrentFocusHolder_requestGranted() {
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfo = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_1).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfo);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfo), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_forTwoDifferentZones_requestGranted() {
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient1 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_1).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoClient1);

        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_2)).thenReturn(SECONDARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient2 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_2).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoClient2);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient1), anyInt(), eq(mAudioPolicy));

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient2), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_forTwoDifferentZones_abandonInOne_requestGranted() {
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient1 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_1).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoClient1);

        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_2)).thenReturn(SECONDARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient2 = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_2).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoClient2);

        mCarZonesAudioFocus.onAudioFocusAbandon(audioFocusInfoClient2);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient1), anyInt(), eq(mAudioPolicy));

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient2), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_withBundleFocusRequest_requestGranted() {
        when(mCarAudioService.isAudioZoneIdValid(PRIMARY_ZONE_ID)).thenReturn(true);

        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        Bundle bundle = new Bundle();
        bundle.putInt(CarAudioManager.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID,
                PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoClient = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(MEDIA_CLIENT_UID_1).setBundle(bundle).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoClient);

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoClient), anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_repeatForSameZone_requestGranted() {
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoMediaClient = new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                        .setClientId(MEDIA_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientUid(MEDIA_CLIENT_UID_1).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoMediaClient);

        when(mCarAudioService.getZoneIdForUid(NAVIGATION_CLIENT_UID)).thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setClientId(NAVIGATION_CLIENT_ID).setGainRequest(AUDIOFOCUS_GAIN)
                .setClientUid(NAVIGATION_CLIENT_UID).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoNavClient);

        verify(mMockAudioManager)
                .dispatchAudioFocusChange(eq(audioFocusInfoMediaClient),
                        anyInt(), eq(mAudioPolicy));

        verify(mMockAudioManager, never())
                .dispatchAudioFocusChange(eq(audioFocusInfoNavClient),
                        anyInt(), eq(mAudioPolicy));
    }

    @Test
    public void onAudioFocusRequest_forNavigationWhileOnCall_rejectNavOnCall_requestFailed() {
        doReturn(true).when(mCarAudioService).isAudioZoneIdValid(PRIMARY_ZONE_ID);
        setUpRejectNavigationOnCallValue(true);
        mCarZonesAudioFocus.updateUserForZoneId(PRIMARY_ZONE_ID, TEST_USER_ID);

        doReturn(PRIMARY_ZONE_ID).when(mCarAudioService).getZoneIdForUid(CALL_CLIENT_UID);
        AudioFocusInfo audioFocusInfoCallClient = new AudioFocusInfoBuilder()
                .setUsage(USAGE_VOICE_COMMUNICATION)
                .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setClientId(CALL_CLIENT_ID)
                .setClientUid(CALL_CLIENT_UID).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoCallClient);

        doReturn(PRIMARY_ZONE_ID).when(mCarAudioService).getZoneIdForUid(NAVIGATION_CLIENT_UID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setClientId(NAVIGATION_CLIENT_ID)
                        .setClientUid(NAVIGATION_CLIENT_UID).createAudioFocusInfo();

        mCarZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoNavClient, AUDIOFOCUS_REQUEST_GRANTED);
        verify(mMockAudioManager).setFocusRequestResult(audioFocusInfoNavClient,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }

    @Test
    public void onAudioFocusRequest_forNavigationWhileOnCall_noRejectNavOnCall_requestSucceeds() {
        doReturn(true).when(mCarAudioService).isAudioZoneIdValid(PRIMARY_ZONE_ID);
        setUpRejectNavigationOnCallValue(false);
        mCarZonesAudioFocus.updateUserForZoneId(PRIMARY_ZONE_ID, TEST_USER_ID);

        doReturn(PRIMARY_ZONE_ID).when(mCarAudioService).getZoneIdForUid(CALL_CLIENT_UID);
        AudioFocusInfo audioFocusInfoCallClient = new AudioFocusInfoBuilder()
                .setUsage(USAGE_VOICE_COMMUNICATION)
                .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setClientId(CALL_CLIENT_ID)
                .setClientUid(CALL_CLIENT_UID).createAudioFocusInfo();

        requestFocusAndAssertIfRequestFailed(audioFocusInfoCallClient);

        doReturn(PRIMARY_ZONE_ID).when(mCarAudioService).getZoneIdForUid(NAVIGATION_CLIENT_UID);
        AudioFocusInfo audioFocusInfoNavClient =
                new AudioFocusInfoBuilder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setClientId(NAVIGATION_CLIENT_ID)
                        .setClientUid(NAVIGATION_CLIENT_UID).createAudioFocusInfo();


        mCarZonesAudioFocus
                .onAudioFocusRequest(audioFocusInfoNavClient, AUDIOFOCUS_REQUEST_GRANTED);
        verify(mMockAudioManager).setFocusRequestResult(audioFocusInfoNavClient,
                AUDIOFOCUS_REQUEST_GRANTED, mAudioPolicy);
    }

    private void requestFocusAndAssertIfRequestFailed(AudioFocusInfo audioFocusClient) {
        requestFocusAndAssertIfRequestDiffers(audioFocusClient, AUDIOFOCUS_REQUEST_GRANTED);
    }

    private void requestFocusAndAssertIfRequestDiffers(AudioFocusInfo audioFocusClient,
            int expectedAudioFocusResults) {
        mCarZonesAudioFocus.onAudioFocusRequest(audioFocusClient, expectedAudioFocusResults);
        verify(mMockAudioManager)
                .setFocusRequestResult(audioFocusClient, expectedAudioFocusResults, mAudioPolicy);
    }

    private CarAudioZone[] generateAudioZones() {
        mPrimaryAudioZone = new CarAudioZone(PRIMARY_ZONE_ID, "Primary zone");
        mSecondaryAudioZone = new CarAudioZone(SECONDARY_ZONE_ID, "Secondary zone");
        CarAudioZone[] zones = {mPrimaryAudioZone, mSecondaryAudioZone};
        return zones;
    }

    private void setUpRejectNavigationOnCallValue(boolean rejectNavigationOnCall) {
        doReturn(mContentResolver).when(mCarAudioSettings).getContentResolver();
        doReturn(rejectNavigationOnCall).when(mCarAudioSettings)
                .isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID);
    }

    public class AudioFocusInfoBuilder {
        private int mUsage;
        private int mClientUid;
        private String mClientId;
        private int mGainRequest;
        private String mPackageName = PACKAGE_NAME;
        private Bundle mBundle = null;
        private int mLossReceived = AudioManager.AUDIOFOCUS_NONE;
        private int mFlags = AUDIOFOCUS_FLAG;
        private int mSdk = Build.VERSION.SDK_INT;

        public AudioFocusInfoBuilder setUsage(int usage) {
            mUsage = usage;
            return this;
        }

        public AudioFocusInfoBuilder setClientUid(int clientUid) {
            mClientUid = clientUid;
            return this;
        }

        public AudioFocusInfoBuilder setClientId(String clientId) {
            mClientId = clientId;
            return this;
        }

        public AudioFocusInfoBuilder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }

        public AudioFocusInfoBuilder setGainRequest(int gainRequest) {
            mGainRequest = gainRequest;
            return this;
        }

        public AudioFocusInfoBuilder setLossReceived(int lossReceived) {
            mLossReceived = lossReceived;
            return this;
        }

        public AudioFocusInfoBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        public AudioFocusInfoBuilder setSdk(int sdk) {
            mSdk = sdk;
            return this;
        }

        public AudioFocusInfoBuilder setBundle(Bundle bundle) {
            mBundle = bundle;
            return this;
        }

        public AudioFocusInfo createAudioFocusInfo() {
            AudioAttributes.Builder builder = new AudioAttributes.Builder().setUsage(mUsage);
            if (mBundle != null) {
                builder = builder.addBundle(mBundle);
            }
            AudioAttributes audioAttributes = builder.build();
            return new AudioFocusInfo(audioAttributes, mClientUid, mClientId,
                    mPackageName, mGainRequest, mLossReceived, mFlags, mSdk);
        }
    }
}
