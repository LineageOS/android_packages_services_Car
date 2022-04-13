/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.util.SparseArray;

import org.junit.Before;
import org.mockito.Mock;

abstract class CarZonesAudioFocusTestBase {
    protected static final String MEDIA_CLIENT_ID = "media-client-id";
    protected static final String NAVIGATION_CLIENT_ID = "nav-client-id";
    protected static final String CALL_CLIENT_ID = "call-client-id";
    protected static final int PRIMARY_ZONE_ID = CarAudioManager.PRIMARY_AUDIO_ZONE;
    protected static final int SECONDARY_ZONE_ID = CarAudioManager.PRIMARY_AUDIO_ZONE + 1;
    protected static final int MEDIA_CLIENT_UID_1 = 1086753;
    protected static final int MEDIA_CLIENT_UID_2 = 1000009;
    protected static final int NAVIGATION_CLIENT_UID = 1010101;
    protected static final int TEST_USER_ID = 10;
    protected static final int CALL_CLIENT_UID = 1086753;

    @Mock
    protected AudioManager mMockAudioManager;
    @Mock
    protected AudioPolicy mAudioPolicy;
    @Mock
    protected CarAudioService mCarAudioService;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private CarAudioSettings mCarAudioSettings;
    @Mock
    protected CarZonesAudioFocus.CarFocusCallback mMockCarFocusCallback;
    @Mock
    private PackageManager mMockPackageManager;

    private SparseArray<CarAudioZone> mCarAudioZones;

    @Before
    public void setUp() {
        mCarAudioZones = generateAudioZones();
        when(mCarAudioService.getZoneIdForUid(MEDIA_CLIENT_UID_1)).thenReturn(PRIMARY_ZONE_ID);
    }

    protected AudioFocusInfo generateCallRequestForPrimaryZone() {
        return new AudioFocusInfoBuilder().setUsage(USAGE_VOICE_COMMUNICATION)
                .setGainRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setClientId(CALL_CLIENT_ID)
                .setClientUid(CALL_CLIENT_UID).createAudioFocusInfo();
    }

    protected AudioFocusInfo generateMediaRequestForPrimaryZone(boolean isDelayedFocusEnabled) {
        return new AudioFocusInfoBuilder().setUsage(USAGE_MEDIA)
                .setGainRequest(AUDIOFOCUS_GAIN)
                .setClientId(MEDIA_CLIENT_ID)
                .setDelayedFocusRequestEnable(isDelayedFocusEnabled)
                .setClientUid(MEDIA_CLIENT_UID_1).createAudioFocusInfo();
    }

    protected void requestFocusAndAssertIfRequestNotGranted(CarZonesAudioFocus carZonesAudioFocus,
            AudioFocusInfo audioFocusClient) {
        requestFocusAndAssertIfRequestDiffers(carZonesAudioFocus, audioFocusClient,
                AUDIOFOCUS_REQUEST_GRANTED);
    }

    private void requestFocusAndAssertIfRequestDiffers(CarZonesAudioFocus carZonesAudioFocus,
            AudioFocusInfo audioFocusClient, int expectedAudioFocusResults) {
        carZonesAudioFocus.onAudioFocusRequest(audioFocusClient, expectedAudioFocusResults);
        verify(mMockAudioManager)
                .setFocusRequestResult(audioFocusClient, expectedAudioFocusResults, mAudioPolicy);
    }

    private SparseArray<CarAudioZone> generateAudioZones() {
        SparseArray<CarAudioZone> zones = new SparseArray<>(2);
        zones.put(PRIMARY_ZONE_ID, new CarAudioZone(PRIMARY_ZONE_ID, "Primary zone"));
        zones.put(SECONDARY_ZONE_ID, new CarAudioZone(SECONDARY_ZONE_ID, "Secondary zone"));
        return zones;
    }

    protected CarZonesAudioFocus getCarZonesAudioFocus() {
        CarZonesAudioFocus carZonesAudioFocus =
                CarZonesAudioFocus.createCarZonesAudioFocus(mMockAudioManager, mMockPackageManager,
                        mCarAudioZones,
                        mCarAudioSettings, mMockCarFocusCallback);
        carZonesAudioFocus.setOwningPolicy(mCarAudioService, mAudioPolicy);


        return carZonesAudioFocus;
    }

    protected void setUpRejectNavigationOnCallValue(boolean rejectNavigationOnCall) {
        when(mCarAudioSettings.getContentResolverForUser(TEST_USER_ID))
                .thenReturn(mContentResolver);
        when(mCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID))
                .thenReturn(rejectNavigationOnCall);
    }
}
