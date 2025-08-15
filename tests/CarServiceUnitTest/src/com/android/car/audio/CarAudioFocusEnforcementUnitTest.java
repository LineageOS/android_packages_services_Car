/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.media.AudioAttributes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioFocusEnforcementUnitTest {

    private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder().setUsage(
            USAGE_MEDIA).build();
    private static final AudioAttributes GAME_ATTRIBUTES = new AudioAttributes.Builder().setUsage(
            USAGE_GAME).build();
    private static final AudioAttributes EMERGENCY_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();
    private static final AudioAttributes SAFETY_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_SAFETY).build();
    private static final AudioAttributes VOICE_COMMUNICATION_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION).build();
    private static final AudioAttributes CALL_ASSISTANT_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_CALL_ASSISTANT).build();
    private static final AudioAttributes VOICE_COMMUNICATION_SIGNALLING_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION_SIGNALLING).build();

    @Test
    public void setEnforceableAttributes_withNullSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> enforcement.setEnforceableAttributes(/* audioAttributesToSilence= */ null));

        assertWithMessage("Null audio attributes exception").that(thrown)
                .hasMessageThat().contains("audio attributes can not be null");
    }

    @Test
    public void setEnforceableAttributes_withEmergencyUsageInSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> enforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, EMERGENCY_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception").that(
                thrown).hasMessageThat().contains("does not support usage EMERGENCY");
    }

    @Test
    public void setEnforceableAttributes_withSafetyUsageInSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> enforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, SAFETY_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for safety usage").that(
                thrown).hasMessageThat().contains("does not support usage SAFETY");
    }

    @Test
    public void setEnforceableAttributes_withVoiceComUsageInSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> enforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, VOICE_COMMUNICATION_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for voice communication usage").that(
                thrown).hasMessageThat().contains("does not support usage VOICE_COMMUNICATION");
    }

    @Test
    public void setEnforceableAttributes_withCallAssistantUsageInSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> enforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, CALL_ASSISTANT_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for call assistant usage").that(
                thrown).hasMessageThat().contains("does not support usage CALL_ASSISTANT");
    }

    @Test
    public void setEnforceableAttributes_withVoiceComSignallingInSilenceList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> enforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, VOICE_COMMUNICATION_SIGNALLING_ATTRIBUTES)));

        assertWithMessage(
                "Invalid audio attributes exception for voice comms signalling usage").that(
                thrown).hasMessageThat().contains(
                "does not support usage VOICE_COMMUNICATION_SIGNALLING");
    }

    @Test
    public void setEnforceableAttributes_withValidAttributesInSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));

        assertWithMessage("Enforceable audio attributes")
                .that(enforcement.getEnforceableAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setEnforceableAttributes_withDuplicateAttributesInSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES, MEDIA_ATTRIBUTES));

        assertWithMessage("Enforceable audio attributes with duplicates")
                .that(enforcement.getEnforceableAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setEnforceableAttributes_withEmptyAttributesInSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setEnforceableAttributes(List.of());

        assertWithMessage("Enforceable audio attributes with empty list")
                .that(enforcement.getEnforceableAttributes()).isEmpty();
    }

    @Test
    public void setDoNotSilenceAttributes_withNullList_throws() {
        var enforcement = new CarAudioFocusEnforcement();

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> enforcement.setDoNotSilenceAttributes(/* doNotSilenceAttributes= */ null));

        assertWithMessage("Null do not silence audio attributes exception").that(
                thrown).hasMessageThat().contains("Do not silence audio attributes");
    }

    @Test
    public void setDoNotSilenceAttributes_withDuplicateAttributesInDoNotSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setDoNotSilenceAttributes(List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES,
                MEDIA_ATTRIBUTES));

        assertWithMessage("Do not silence audio attributes with duplicates")
                .that(enforcement.getDoNotSilenceAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setDoNotSilenceAttributes_withEmptyAttributesInDoNotSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setDoNotSilenceAttributes(List.of());

        assertWithMessage("Do not silence audio attributes with empty list")
                .that(enforcement.getDoNotSilenceAttributes()).isEmpty();
    }

    @Test
    public void setDoNotSilenceAttributes_withValidAttributesInDoNotSilenceList() {
        var enforcement = new CarAudioFocusEnforcement();

        enforcement.setDoNotSilenceAttributes(List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));

        assertWithMessage("Do not silence audio attributes with empty list")
                .that(enforcement.getDoNotSilenceAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }
}
