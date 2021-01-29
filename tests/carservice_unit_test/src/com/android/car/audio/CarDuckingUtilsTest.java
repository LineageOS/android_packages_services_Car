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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.AudioAttributes;
import android.media.AudioFocusInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class CarDuckingUtilsTest {
    private static final String MEDIA_ADDRESS = "media";
    private static final String EMERGENCY_ADDRESS = "emergency";
    private static final String CALL_ADDRESS = "call";
    private static final String NAVIGATION_ADDRESS = "navigation";

    // To ensure a consistent ducking experience, if context A ducks context B, then context B
    // should in turn only duck a subset of contexts ducked by context A. In this way, we won't have
    // a situation where context A would normally not duck context C, but because context B was also
    // holding focus, context C ends up being ducked despite B being ducked.
    @Test
    public void sContextsToDuck_verifyAllDuckedContextsDuckSubsets() {
        for (int i = 0; i < CarDuckingUtils.sContextsToDuck.size(); i++) {
            int context = CarDuckingUtils.sContextsToDuck.keyAt(i);
            int[] contextsToDuck = CarDuckingUtils.sContextsToDuck.valueAt(i);

            for (int duckedContext : contextsToDuck) {
                List<Integer> subcontextsToDuck = Arrays
                        .stream(CarDuckingUtils.sContextsToDuck.get(duckedContext))
                        .boxed()
                        .collect(Collectors.toList());

                assertWithMessage("Context "
                        + CarAudioContext.toString(duckedContext) + " ducks contexts not ducked by "
                        + CarAudioContext.toString(context))
                        .that(contextsToDuck)
                        .asList()
                        .containsAtLeastElementsIn(subcontextsToDuck);
            }
        }
    }

    @Test
    public void sContextsToDuck_verifyContextsDontDuckThemselves() {
        for (int i = 0; i < CarDuckingUtils.sContextsToDuck.size(); i++) {
            int context = CarDuckingUtils.sContextsToDuck.keyAt(i);
            int[] contextsToDuck = CarDuckingUtils.sContextsToDuck.valueAt(i);

            assertWithMessage("Context " + CarAudioContext.toString(context) + " ducks itself")
                    .that(contextsToDuck)
                    .asList()
                    .doesNotContain(context);
        }
    }

    @Test
    public void getUsagesHoldingFocus_withNoHolders_returnsEmptyArray() {
        int[] usages = CarDuckingUtils.getUsagesHoldingFocus(new ArrayList<>());

        assertThat(usages).isEmpty();
    }

    @Test
    public void getUsagesHoldingFocus_removesDuplicateUsages() {
        List<AudioFocusInfo> focusHolders = List.of(
                generateAudioFocusInfoForUsage(USAGE_NOTIFICATION),
                generateAudioFocusInfoForUsage(USAGE_MEDIA),
                generateAudioFocusInfoForUsage(USAGE_NOTIFICATION));

        int[] usages = CarDuckingUtils.getUsagesHoldingFocus(focusHolders);

        assertThat(usages).hasLength(2);
        assertThat(usages).asList().containsExactly(USAGE_MEDIA, USAGE_NOTIFICATION);
    }

    @Test
    public void getUsagesHoldingFocus_includesSystemUsages() {
        List<AudioFocusInfo> focusHolders = List.of(generateAudioFocusInfoForUsage(USAGE_MEDIA),
                generateAudioFocusInfoForSystemUsage(USAGE_SAFETY),
                generateAudioFocusInfoForSystemUsage(USAGE_EMERGENCY));

        int[] usages = CarDuckingUtils.getUsagesHoldingFocus(focusHolders);

        assertThat(usages).hasLength(3);
        assertThat(usages).asList().containsExactly(USAGE_MEDIA, USAGE_SAFETY, USAGE_EMERGENCY);
    }

    @Test
    public void getAddressesToDuck_withOneUsage_returnsEmptyList() {
        CarAudioZone mockZone = generateAudioZoneMock();

        List<String> addresses = CarDuckingUtils.getAddressesToDuck(new int[]{USAGE_MEDIA,
                USAGE_GAME}, mockZone);

        assertThat(addresses).isEmpty();
    }

    @Test
    public void getAddressesToDuck_withMultipleUsagesForTheSameContext_returnsEmptyList() {
        CarAudioZone mockZone = generateAudioZoneMock();

        List<String> addresses = CarDuckingUtils.getAddressesToDuck(new int[]{USAGE_MEDIA,
                USAGE_GAME}, mockZone);

        assertThat(addresses).isEmpty();
    }

    @Test
    public void getAddressesToDuck_onlyReturnsDevicesForUsagesHoldingFocus() {
        CarAudioZone mockZone = generateAudioZoneMock();
        int[] usages =
                new int[]{USAGE_MEDIA, USAGE_EMERGENCY, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE};

        List<String> addresses = CarDuckingUtils.getAddressesToDuck(usages, mockZone);

        assertThat(addresses).containsExactly(MEDIA_ADDRESS, NAVIGATION_ADDRESS);
    }

    @Test
    public void getAddressesToDuck_withDuckedAndUnduckedContextsSharingDevice_excludesThatDevice() {
        CarAudioZone mockZone = generateAudioZoneMock();
        when(mockZone.getAddressForContext(CarAudioContext.SAFETY)).thenReturn(NAVIGATION_ADDRESS);
        int[] usages = new int[]{USAGE_MEDIA, USAGE_SAFETY, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE};

        List<String> addresses = CarDuckingUtils.getAddressesToDuck(usages, mockZone);

        assertThat(addresses).containsExactly(MEDIA_ADDRESS);
    }

    @Test
    public void getAddressesToDuck_withDuckedContextsSharingADevice_includesAddressOnce() {
        CarAudioZone mockZone = generateAudioZoneMock();
        when(mockZone.getAddressForContext(CarAudioContext.ALARM)).thenReturn(MEDIA_ADDRESS);
        int[] usages = new int[]{USAGE_MEDIA, USAGE_EMERGENCY, USAGE_ALARM};

        List<String> addresses = CarDuckingUtils.getAddressesToDuck(usages, mockZone);

        assertThat(addresses).containsExactly(MEDIA_ADDRESS);
    }


    @Test
    public void getAddressesToUnduck_onlyReturnsAddressesThatWerePreviouslyDucked() {
        List<String> oldAddressesToDuck = List.of("one", "three", "four");
        List<String> addressesToDuck = List.of("one", "two");

        List<String> result = CarDuckingUtils.getAddressesToUnduck(addressesToDuck,
                oldAddressesToDuck);

        assertThat(result).containsExactly("three", "four");
    }

    @Test
    public void getAddressesToUnduck_withNoPreviouslyDuckedAddresses_returnsEmptyArray() {
        List<String> oldAddressesToDuck = new ArrayList<>();
        List<String> addressesToDuck = List.of("one", "two");

        List<String> result = CarDuckingUtils.getAddressesToUnduck(addressesToDuck,
                oldAddressesToDuck);

        assertThat(result).isEmpty();
    }

    private static AudioFocusInfo generateAudioFocusInfoForUsage(int usage) {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usage).build();
        return new AudioFocusInfo(attributes, 0, "client_id", "package.name",
                AUDIOFOCUS_GAIN_TRANSIENT, 0, 0, 0);
    }

    private static AudioFocusInfo generateAudioFocusInfoForSystemUsage(int systemUsage) {
        AudioAttributes attributes = new AudioAttributes.Builder().setSystemUsage(
                systemUsage).build();
        return new AudioFocusInfo(attributes, 0, "client_id", "package.name",
                AUDIOFOCUS_GAIN_TRANSIENT, 0, 0, 0);
    }

    private static CarAudioZone generateAudioZoneMock() {
        CarAudioZone mockZone = mock(CarAudioZone.class);
        when(mockZone.getAddressForContext(CarAudioContext.MUSIC)).thenReturn(MEDIA_ADDRESS);
        when(mockZone.getAddressForContext(CarAudioContext.EMERGENCY)).thenReturn(
                EMERGENCY_ADDRESS);
        when(mockZone.getAddressForContext(CarAudioContext.CALL)).thenReturn(CALL_ADDRESS);
        when(mockZone.getAddressForContext(CarAudioContext.NAVIGATION)).thenReturn(
                NAVIGATION_ADDRESS);

        return mockZone;
    }
}
