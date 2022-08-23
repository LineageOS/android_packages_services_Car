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
import static android.media.AudioAttributes.USAGE_NOTIFICATION;

import static com.android.car.audio.CarAudioContext.MUSIC;
import static com.android.car.audio.CarAudioContext.NOTIFICATION;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.audio.policy.configuration.V7_0.AudioUsage;
import android.hardware.audio.common.PlaybackTrackMetadata;
import android.hardware.automotive.audiocontrol.DuckingInfo;
import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceAddress;
import android.media.audio.common.AudioDeviceDescription;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarHalAudioUtilsTest {
    private static final int ZONE_ID = 0;
    private static final String MEDIA_ADDRESS = "MEDIA_ADDRESS";
    private static final String NOTIFICATION_ADDRESS = "NOTIFICATION_ADDRESS";
    private static final List<String> ADDRESSES_TO_DUCK = List.of("address1", "address2");
    private static final List<String> ADDRESSES_TO_UNDUCK = List.of("address3", "address4");
    private static final int[] USAGES_HOLDING_FOCUS = {USAGE_MEDIA, USAGE_NOTIFICATION};
    private static final String[] USAGES_LITERAL_HOLDING_FOCUS = {
        AudioUsage.AUDIO_USAGE_MEDIA.toString(), AudioUsage.AUDIO_USAGE_NOTIFICATION.toString()
    };

    private final List<PlaybackTrackMetadata> mPlaybackTrackMetadataHoldingFocus =
            new ArrayList<>();

    private final CarAudioZone mCarAudioZone = generateZoneMock();

    @Rule public MockitoRule rule = MockitoJUnit.rule();

    @Before
    public void setUp() {
        for (int index = 0; index < USAGES_HOLDING_FOCUS.length; index++) {
            PlaybackTrackMetadata playbackTrackMetadata = new PlaybackTrackMetadata();
            playbackTrackMetadata.usage = USAGES_HOLDING_FOCUS[index];

            AudioDeviceDescription add = new AudioDeviceDescription();
            add.connection = new String();
            AudioDevice ad = new AudioDevice();
            ad.type = add;
            ad.address =
                    AudioDeviceAddress.id(
                            mCarAudioZone.getAddressForContext(
                                    CarAudioContext.getContextForUsage(
                                            USAGES_HOLDING_FOCUS[index])));
            playbackTrackMetadata.sourceDevice = ad;

            mPlaybackTrackMetadataHoldingFocus.add(playbackTrackMetadata);
        }
    }

    @Test
    public void generateDuckingInfo_succeeds() {
        DuckingInfo duckingInfo = CarHalAudioUtils.generateDuckingInfo(getCarDuckingInfo());

        assertWithMessage("Generated duck info zone ").that(duckingInfo.zoneId).isEqualTo(ZONE_ID);
        assertWithMessage("Generated duck info addresses to duck")
                .that(duckingInfo.deviceAddressesToDuck)
                .asList()
                .containsExactlyElementsIn(ADDRESSES_TO_DUCK);
        assertWithMessage("Generated duck info addresses to unduck")
                .that(duckingInfo.deviceAddressesToUnduck)
                .asList()
                .containsExactlyElementsIn(ADDRESSES_TO_UNDUCK);
        assertWithMessage("Generated duck info playback metadata holding focus")
                .that(duckingInfo.playbackMetaDataHoldingFocus)
                .asList()
                .containsExactlyElementsIn(mPlaybackTrackMetadataHoldingFocus);
    }

    @Test
    public void usageToMetadata_succeeds() {
        PlaybackTrackMetadata playbackTrackMetadata =
                CarHalAudioUtils.usageToMetadata(USAGE_MEDIA, mCarAudioZone);
        assertWithMessage("Playback Track Metadata usage")
                .that(playbackTrackMetadata.usage)
                .isEqualTo(USAGE_MEDIA);
        assertWithMessage("Playback Track Metadata source device address")
                .that(playbackTrackMetadata.sourceDevice.address.getId())
                .isEqualTo(MEDIA_ADDRESS);
    }

    @Test
    public void usageToMetadata_withNullZone_succeeds() {
        PlaybackTrackMetadata playbackTrackMetadata =
                CarHalAudioUtils.usageToMetadata(USAGE_MEDIA, /*CarAudioZone=*/ null);
        assertWithMessage("Playback Track Metadata usage")
                .that(playbackTrackMetadata.usage)
                .isEqualTo(USAGE_MEDIA);
        String emptyAddress = new String("");
        assertWithMessage("Playback Track Metadata source device address")
                .that(playbackTrackMetadata.sourceDevice.address.getId())
                .isEqualTo(emptyAddress);
    }

    @Test
    public void usagesToMetadatas_succeeds() {
        List<PlaybackTrackMetadata> playbackTrackMetadataList =
                CarHalAudioUtils.usagesToMetadatas(USAGES_HOLDING_FOCUS, mCarAudioZone);

        assertWithMessage(
                        "Converted PlaybackTrackMetadata size for usages holding focus size %s",
                        USAGES_HOLDING_FOCUS.length)
                .that(playbackTrackMetadataList.size())
                .isEqualTo(USAGES_HOLDING_FOCUS.length);

        int[] usages = new int[playbackTrackMetadataList.size()];
        String[] addresses = new String[playbackTrackMetadataList.size()];
        for (int index = 0; index < playbackTrackMetadataList.size(); index++) {
            PlaybackTrackMetadata playbackTrackMetadata = playbackTrackMetadataList.get(index);
            usages[index] = playbackTrackMetadata.usage;
            addresses[index] = playbackTrackMetadata.sourceDevice.address.getId();
        }
        assertWithMessage("Converted usages to PlaybackTrackMetadata usage")
                .that(usages)
                .asList()
                .containsExactly(USAGE_MEDIA, USAGE_NOTIFICATION);
        assertWithMessage("Converted usages to PlaybackTrackMetadata addresses")
                .that(addresses)
                .asList()
                .containsExactly(MEDIA_ADDRESS, NOTIFICATION_ADDRESS);
    }

    @Test
    public void usagesToMetadatas_withNullZone_succeeds() {
        List<PlaybackTrackMetadata> playbackTrackMetadataList =
                CarHalAudioUtils.usagesToMetadatas(USAGES_HOLDING_FOCUS, /*CarAudioZone=*/ null);

        assertWithMessage(
                        "Converted PlaybackTrackMetadata size for usages holding focus size %s",
                        USAGES_HOLDING_FOCUS.length)
                .that(playbackTrackMetadataList.size())
                .isEqualTo(USAGES_HOLDING_FOCUS.length);

        int[] usages = new int[playbackTrackMetadataList.size()];
        String[] addresses = new String[playbackTrackMetadataList.size()];
        for (int index = 0; index < playbackTrackMetadataList.size(); index++) {
            PlaybackTrackMetadata playbackTrackMetadata = playbackTrackMetadataList.get(index);
            usages[index] = playbackTrackMetadata.usage;
            String emptyAddress = new String("");
            assertWithMessage("Source device address Id for Usage %s", usages[index])
                    .that(playbackTrackMetadata.sourceDevice.address.getId())
                    .isEqualTo(emptyAddress);
        }
        assertWithMessage("Converted usages to PlaybackTrackMetadata usages")
                .that(usages)
                .asList()
                .containsExactly(USAGE_MEDIA, USAGE_NOTIFICATION);
    }

    @Test
    public void metadataToUsage_succeeds() {
        for (int index = 0; index < mPlaybackTrackMetadataHoldingFocus.size(); index++) {
            PlaybackTrackMetadata playbackTrackMetadata =
                    mPlaybackTrackMetadataHoldingFocus.get(index);
            int usage = CarHalAudioUtils.metadataToUsage(playbackTrackMetadata);
            assertWithMessage(
                            "Buld Converted PlaybackTrackMetadata[%s] usage", playbackTrackMetadata)
                    .that(usage)
                    .isEqualTo(playbackTrackMetadata.usage);
        }
    }

    @Test
    public void metadatasToUsages_succeeds() {
        int[] usages = CarHalAudioUtils.metadatasToUsages(mPlaybackTrackMetadataHoldingFocus);
        assertThat(usages.length).isEqualTo(mPlaybackTrackMetadataHoldingFocus.size());
        assertThat(usages).asList().containsExactly(USAGE_MEDIA, USAGE_NOTIFICATION);

        for (int index = 0; index < usages.length; index++) {
            int usage = usages[index];
            PlaybackTrackMetadata playbackTrackMetadata =
                    mPlaybackTrackMetadataHoldingFocus.get(index);
            assertWithMessage(
                            "Buld Converted PlaybackTrackMetadata[%s] usage", playbackTrackMetadata)
                    .that(usage)
                    .isEqualTo(playbackTrackMetadata.usage);
        }
    }

    @Test
    public void metadatasToUsageStrings_succeeds() {
        String[] usageLiteralsForMetadata =
                CarHalAudioUtils.metadatasToUsageStrings(mPlaybackTrackMetadataHoldingFocus);
        assertThat(usageLiteralsForMetadata.length)
                .isEqualTo(mPlaybackTrackMetadataHoldingFocus.size());
        assertThat(usageLiteralsForMetadata)
                .asList()
                .containsExactlyElementsIn(USAGES_LITERAL_HOLDING_FOCUS);
    }

    private CarDuckingInfo getCarDuckingInfo() {
        return new CarDuckingInfo(
                ZONE_ID,
                ADDRESSES_TO_DUCK,
                ADDRESSES_TO_UNDUCK,
                mPlaybackTrackMetadataHoldingFocus);
    }

    private static CarAudioZone generateZoneMock() {
        CarAudioZone zone = mock(CarAudioZone.class);
        when(zone.getId()).thenReturn(ZONE_ID);
        when(zone.getAddressForContext(MUSIC)).thenReturn(MEDIA_ADDRESS);
        when(zone.getAddressForContext(NOTIFICATION)).thenReturn(NOTIFICATION_ADDRESS);
        return zone;
    }
}
