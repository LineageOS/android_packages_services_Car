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
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VIRTUAL_SOURCE;

import static com.android.car.audio.CarAudioContext.ALARM;
import static com.android.car.audio.CarAudioContext.ANNOUNCEMENT;
import static com.android.car.audio.CarAudioContext.CALL;
import static com.android.car.audio.CarAudioContext.CALL_RING;
import static com.android.car.audio.CarAudioContext.EMERGENCY;
import static com.android.car.audio.CarAudioContext.INVALID;
import static com.android.car.audio.CarAudioContext.MUSIC;
import static com.android.car.audio.CarAudioContext.NAVIGATION;
import static com.android.car.audio.CarAudioContext.NOTIFICATION;
import static com.android.car.audio.CarAudioContext.SAFETY;
import static com.android.car.audio.CarAudioContext.SYSTEM_SOUND;
import static com.android.car.audio.CarAudioContext.VEHICLE_STATUS;
import static com.android.car.audio.CarAudioContext.VOICE_COMMAND;
import static com.android.car.audio.CarAudioContext.isCriticalAudioContext;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.media.AudioAttributes;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.audio.CarAudioContext.AudioContext;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class CarAudioContextTest {
    private static final int INVALID_CONTEXT = -5;

    private static final AudioAttributes UNKNOWN_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_UNKNOWN).build();
    private static final AudioAttributes MEDIA_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes GAME_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_GAME).build();

    @Test
    public void getContextForAudioAttributes_forAttributeWithValidUsage_returnsContext() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();

        assertWithMessage("Context for valid audio attributes usage")
                .that(CarAudioContext.getContextForAttributes(attributes)).isEqualTo(MUSIC);
    }

    @Test
    public void getContextForAudioAttributes_forAttributesWithInvalidUsage_returnsInvalidContext() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setUsage(USAGE_VIRTUAL_SOURCE).build();

        assertWithMessage("Context for invalid audio attribute")
                .that(CarAudioContext.getContextForAttributes(attributes)).isEqualTo(INVALID);
    }

    @Test
    public void getAudioAttributesForContext_withValidContext_returnsAttributes() {
        AudioAttributes[] attributes = CarAudioContext.getAudioAttributesForContext(MUSIC);
        assertWithMessage("Music context's audio attributes")
                .that(attributes).asList().containsExactly(UNKNOWN_USAGE_ATTRIBUTE,
                MEDIA_USAGE_ATTRIBUTE, GAME_USAGE_ATTRIBUTE);
    }

    @Test
    public void getAudioAttributesForContext_withInvalidContext_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            CarAudioContext.getAudioAttributesForContext(INVALID_CONTEXT);
        });

        assertWithMessage("Invalid context exception").that(thrown)
                .hasMessageThat().contains("Car audio context " + INVALID_CONTEXT + " is invalid");
    }

    @Test
    public void getAudioAttributesForContext_returnsUniqueValuesForAllContexts() {
        Set<AudioAttributes> allUsages = new ArraySet<>();
        for (@AudioContext int audioContext : CarAudioContext.CONTEXTS) {
            AudioAttributes[] attributes =
                    CarAudioContext.getAudioAttributesForContext(audioContext);

            assertWithMessage("Unique audio attributes for context %s", audioContext)
                    .that(allUsages.addAll(Arrays.asList(attributes))).isTrue();
        }
    }

    @Test
    public void getUniqueContextsForAudioAttribute_withEmptyArray_returnsEmptySet() {
        Set<Integer> result =
                CarAudioContext.getUniqueContextsForAudioAttributes(new ArrayList<>());

        assertWithMessage("Empty unique context list").that(result).isEmpty();
    }

    @Test
    public void getUniqueContextsForAudioAttribute_withNullArray_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            CarAudioContext.getUniqueContextsForAudioAttributes(null);
        });

        assertWithMessage("Unique contexts conversion exception")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void getUniqueContextsForAudioAttributes_withMultipleAttributes_filtersDupContexts() {
        List<AudioAttributes> audioAttributes = new ArrayList<>(2);
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_GAME));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));

        Set<Integer> result = CarAudioContext.getUniqueContextsForAudioAttributes(audioAttributes);

        assertWithMessage("Media and Game audio attribute's context")
                .that(result).containsExactly(MUSIC);
    }

    @Test
    public void getUniqueContextsForAudioAttributes_withDiffAttributes_returnsAllUniqueContexts() {
        List<AudioAttributes> audioAttributes = new ArrayList<>(3);
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_EMERGENCY));
        audioAttributes.add(CarAudioContext
                .getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE));

        Set<Integer> result = CarAudioContext.getUniqueContextsForAudioAttributes(audioAttributes);

        assertWithMessage("Separate audio attribute's contexts")
                .that(result).containsExactly(MUSIC, NAVIGATION, EMERGENCY);
    }

    @Test
    public void getUniqueAttributesHoldingFocus_withNoAttributes_returnsEmpty() {
        Set<Integer> contexts =
                CarAudioContext.getUniqueContextsForAudioAttributes(new ArrayList<>());

        assertWithMessage("Empty unique contexts set")
                .that(contexts).isEmpty();
    }

    @Test
    public void getUniqueAttributesHoldingFocus_withDuplicates_returnsSetWithNoDuplicates() {
        List<AudioAttributes> audioAttributes = new ArrayList<>(/* initialCapacity= */ 3);
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION));

        Set<Integer> contexts =
                CarAudioContext.getUniqueContextsForAudioAttributes(audioAttributes);

        assertWithMessage("Non duplicates unique contexts set")
                .that(contexts).containsExactly(MUSIC, NOTIFICATION);
    }

    @Test
    public void getUniqueAttributesHoldingFocus_withSystemAudioAttributes_retSystemContext() {
        List<AudioAttributes> audioAttributes = new ArrayList<>(/* initialCapacity= */ 3);
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_SAFETY));
        audioAttributes.add(CarAudioContext.getAudioAttributeFromUsage(USAGE_EMERGENCY));

        Set<Integer> contexts =
                CarAudioContext.getUniqueContextsForAudioAttributes(audioAttributes);

        assertWithMessage("Non duplicates unique contexts set")
                .that(contexts).containsExactly(MUSIC, SAFETY, EMERGENCY);
    }

    @Test
    public void isCriticalAudioContext_forNonCriticalContexts_returnsFalse() {
        assertWithMessage("Non-critical context INVALID")
                .that(isCriticalAudioContext(CarAudioContext.INVALID)).isFalse();
        assertWithMessage("Non-critical context MUSIC")
                .that(isCriticalAudioContext(CarAudioContext.MUSIC)).isFalse();
        assertWithMessage("Non-critical context NAVIGATION")
                .that(isCriticalAudioContext(CarAudioContext.NAVIGATION)).isFalse();
        assertWithMessage("Non-critical context VOICE_COMMAND")
                .that(isCriticalAudioContext(VOICE_COMMAND)).isFalse();
        assertWithMessage("Non-critical context CALL_RING")
                .that(isCriticalAudioContext(CarAudioContext.CALL_RING)).isFalse();
        assertWithMessage("Non-critical context CALL")
                .that(isCriticalAudioContext(CarAudioContext.CALL)).isFalse();
        assertWithMessage("Non-critical context ALARM")
                .that(isCriticalAudioContext(ALARM)).isFalse();
        assertWithMessage("Non-critical context NOTIFICATION")
                .that(isCriticalAudioContext(CarAudioContext.NOTIFICATION)).isFalse();
        assertWithMessage("Non-critical context SYSTEM_SOUND")
                .that(isCriticalAudioContext(SYSTEM_SOUND)).isFalse();
        assertWithMessage("Non-critical context VEHICLE_STATUS")
                .that(isCriticalAudioContext(CarAudioContext.VEHICLE_STATUS)).isFalse();
        assertWithMessage("Non-critical context ANNOUNCEMENT")
                .that(isCriticalAudioContext(CarAudioContext.ANNOUNCEMENT)).isFalse();
    }

    @Test
    public void isCriticalAudioContext_forCriticalContexts_returnsTrue() {
        assertWithMessage("Critical context EMERGENCY")
                .that(isCriticalAudioContext(CarAudioContext.EMERGENCY)).isTrue();
        assertWithMessage("Critical context SAFETY")
                .that(isCriticalAudioContext(CarAudioContext.SAFETY)).isTrue();
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withNonCriticalUsage_succeeds() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();

        CarAudioContext.AudioAttributesWrapper wrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_MEDIA);

        assertWithMessage("Non critical audio attributes for wrapper")
                .that(wrapper.getAudioAttributes()).isEqualTo(attributes);
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withNonCriticalUsage_toString_succeeds() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();

        CarAudioContext.AudioAttributesWrapper wrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_MEDIA);

        assertWithMessage("Non critical audio attributes for wrapper string")
                .that(wrapper.toString()).isEqualTo(attributes.toString());
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withNonCriticalUsage_equals_succeeds() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
        CarAudioContext.AudioAttributesWrapper wrapper =
                new CarAudioContext.AudioAttributesWrapper(attributes);

        CarAudioContext.AudioAttributesWrapper createdWrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_MEDIA);

        assertWithMessage("Non critical audio attributes wrapper is equal check")
                .that(createdWrapper.equals(wrapper)).isTrue();
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withNonCriticalUsage_hashCode_succeeds() {
        CarAudioContext.AudioAttributesWrapper createdWrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_MEDIA);

        assertWithMessage("Non critical audio attributes wrapper hash code")
                .that(createdWrapper.hashCode()).isEqualTo(Integer.hashCode(USAGE_MEDIA));
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withCriticalUsage_succeeds() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();

        CarAudioContext.AudioAttributesWrapper wrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_EMERGENCY);

        assertWithMessage("Critical audio attributes for wrapper")
                .that(wrapper.getAudioAttributes()).isEqualTo(attributes);
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withCriticalUsage_toString_succeeds() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();

        CarAudioContext.AudioAttributesWrapper wrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_EMERGENCY);

        assertWithMessage("Critical audio attributes for wrapper string")
                .that(wrapper.toString()).isEqualTo(attributes.toString());
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withCriticalUsage_equals_succeeds() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();
        CarAudioContext.AudioAttributesWrapper wrapper =
                new CarAudioContext.AudioAttributesWrapper(attributes);

        CarAudioContext.AudioAttributesWrapper createdWrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_EMERGENCY);

        assertWithMessage("Critical audio attributes wrapper is equal check")
                .that(createdWrapper.equals(wrapper)).isTrue();
    }

    @Test
    public void getAudioAttributeWrapperFromUsage_withCriticalUsage_hashCode_succeeds() {
        CarAudioContext.AudioAttributesWrapper createdWrapper =
                CarAudioContext.getAudioAttributeWrapperFromUsage(USAGE_EMERGENCY);

        assertWithMessage("Critical audio attributes wrapper hash code")
                .that(createdWrapper.hashCode()).isEqualTo(Integer.hashCode(USAGE_EMERGENCY));
    }

    @Test
    public void getAudioAttributeFromUsage_withNonCriticalUsage_succeeds() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();

        AudioAttributes createdAttributes = CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);

        assertWithMessage("Non critical audio attributes")
                .that(createdAttributes).isEqualTo(attributes);
    }

    @Test
    public void getAudioAttributeFromUsage_withCriticalUsage_succeeds() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();

        AudioAttributes createdAttributes =
                CarAudioContext.getAudioAttributeFromUsage(USAGE_EMERGENCY);

        assertWithMessage("Critical audio attributes")
                .that(createdAttributes).isEqualTo(attributes);
    }

    @Test
    public void isRingerOrCallContext_withCallContext_returnsTrue() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(CALL);

        assertWithMessage("Is call check")
                .that(isRingerOrCall).isTrue();
    }

    @Test
    public void isRingerOrCallContext_withRingerContext_returnsTrue() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(CALL_RING);

        assertWithMessage("Is ringer check")
                .that(isRingerOrCall).isTrue();
    }

    @Test
    public void isRingerOrCallContext_withNonCriticalContext_returnsFalse() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(MUSIC);

        assertWithMessage("Non critical context is ringer or call check")
                .that(isRingerOrCall).isFalse();
    }

    @Test
    public void isRingerOrCallContext_withCriticalContext_returnsFalse() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(EMERGENCY);

        assertWithMessage("Critical context is ringer or call check")
                .that(isRingerOrCall).isFalse();
    }

    @Test
    public void preconditionCheckAudioContext_withNonExistentContext_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            CarAudioContext.preconditionCheckAudioContext(-EMERGENCY);
        });

        assertWithMessage("Precondition exception with non existent context check")
                .that(thrown).hasMessageThat()
                .contains("Car audio context " + -EMERGENCY + " is invalid");
    }

    @Test
    public void preconditionCheckAudioContext_withInvalidContext_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            CarAudioContext.preconditionCheckAudioContext(INVALID_CONTEXT);
        });

        assertWithMessage("Precondition exception with invalid context check")
                .that(thrown).hasMessageThat()
                .contains("Car audio context " + INVALID_CONTEXT + " is invalid");
    }

    @Test
    public void getSystemUsages_returnsAllSystemUsages() {
        int[] systemUsages = CarAudioContext.getSystemUsages();

        assertWithMessage("System Usages")
                .that(systemUsages).asList().containsExactly(
                        USAGE_CALL_ASSISTANT,
                        USAGE_EMERGENCY,
                        USAGE_SAFETY,
                        USAGE_VEHICLE_STATUS,
                        USAGE_ANNOUNCEMENT);
    }

    @Test
    public void toString_forNonSystemSoundsContexts_returnsStrings() {
        assertWithMessage("Context String for INVALID")
                .that(CarAudioContext.toString(INVALID)).isEqualTo("INVALID");
        assertWithMessage("Context String for MUSIC")
                .that(CarAudioContext.toString(MUSIC)).isEqualTo("MUSIC");
        assertWithMessage("Context String for NAVIGATION")
                .that(CarAudioContext.toString(NAVIGATION)).isEqualTo("NAVIGATION");
        assertWithMessage("Context String for VOICE_COMMAND")
                .that(CarAudioContext.toString(VOICE_COMMAND)).isEqualTo("VOICE_COMMAND");
        assertWithMessage("Context String for CALL_RING")
                .that(CarAudioContext.toString(CALL_RING)).isEqualTo("CALL_RING");
        assertWithMessage("Context String for CALL")
                .that(CarAudioContext.toString(CALL)).isEqualTo("CALL");
        assertWithMessage("Context String for ALARM")
                .that(CarAudioContext.toString(ALARM)).isEqualTo("ALARM");
        assertWithMessage("Context String for NOTIFICATION")
                .that(CarAudioContext.toString(NOTIFICATION)).isEqualTo("NOTIFICATION");
    }

    @Test
    public void toString_forSystemSoundsContexts_returnsStrings() {
        assertWithMessage("Context String for SYSTEM_SOUND")
                .that(CarAudioContext.toString(SYSTEM_SOUND)).isEqualTo("SYSTEM_SOUND");
        assertWithMessage("Context String for EMERGENCY")
                .that(CarAudioContext.toString(EMERGENCY)).isEqualTo("EMERGENCY");
        assertWithMessage("Context String for SAFETY")
                .that(CarAudioContext.toString(SAFETY)).isEqualTo("SAFETY");
        assertWithMessage("Context String for VEHICLE_STATUS")
                .that(CarAudioContext.toString(VEHICLE_STATUS)).isEqualTo("VEHICLE_STATUS");
        assertWithMessage("Context String for ANNOUNCEMENT")
                .that(CarAudioContext.toString(ANNOUNCEMENT)).isEqualTo("ANNOUNCEMENT");
    }
}
