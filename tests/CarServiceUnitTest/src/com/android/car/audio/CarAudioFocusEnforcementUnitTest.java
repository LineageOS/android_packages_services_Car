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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.builtin.media.AudioManagerHelper;
import android.car.media.EnforcedAudioFocusInfo;
import android.car.media.IEnforceableAudioFocusCallback;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.os.RemoteException;
import android.util.SparseArray;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioFocusEnforcementUnitTest {

    private static final int TEST_UID = 12345;
    private static final int TEST_OTHER_UID = 54321;
    private static final long TIMEOUT_MS = 1000;
    private static final AudioAttributes MEDIA_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes GAME_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_GAME).build();
    private static final AudioAttributes EMERGENCY_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_EMERGENCY).build();
    private static final AudioAttributes SAFETY_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_SAFETY).build();
    private static final AudioAttributes ASSISTANT_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_ASSISTANT).build();
    private static final AudioAttributes VOICE_COMMUNICATION_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION).build();
    private static final AudioAttributes CALL_ASSISTANT_ATTRIBUTES =
            new AudioAttributes.Builder().setSystemUsage(USAGE_CALL_ASSISTANT).build();
    private static final AudioAttributes VOICE_COMMUNICATION_SIGNALLING_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_VOICE_COMMUNICATION_SIGNALLING).build();

    private CarAudioFocusEnforcement mCarAudioFocusEnforcement;

    @Before
    public void setUp() {
        mCarAudioFocusEnforcement = new CarAudioFocusEnforcement();
    }

    @After
    public void tearDown() {
        mCarAudioFocusEnforcement.release();
        mCarAudioFocusEnforcement = null;
    }

    @Test
    public void setEnforceableAttributes_withNullSilenceList_throws() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        /* audioAttributesToSilence= */ null));

        assertWithMessage("Null audio attributes exception").that(thrown)
                .hasMessageThat().contains("audio attributes can not be null");
    }

    @Test
    public void setEnforceableAttributes_withEmergencyUsageInSilenceList_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, EMERGENCY_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception").that(
                thrown).hasMessageThat().contains("does not support usage EMERGENCY");
    }

    @Test
    public void setEnforceableAttributes_withSafetyUsageInSilenceList_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, SAFETY_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for safety usage").that(
                thrown).hasMessageThat().contains("does not support usage SAFETY");
    }

    @Test
    public void setEnforceableAttributes_withVoiceComUsageInSilenceList_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, VOICE_COMMUNICATION_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for voice communication usage").that(
                thrown).hasMessageThat().contains("does not support usage VOICE_COMMUNICATION");
    }

    @Test
    public void setEnforceableAttributes_withCallAssistantUsageInSilenceList_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, CALL_ASSISTANT_ATTRIBUTES)));

        assertWithMessage("Invalid audio attributes exception for call assistant usage").that(
                thrown).hasMessageThat().contains("does not support usage CALL_ASSISTANT");
    }

    @Test
    public void setEnforceableAttributes_withVoiceComSignallingInSilenceList_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mCarAudioFocusEnforcement.setEnforceableAttributes(
                        List.of(GAME_ATTRIBUTES, VOICE_COMMUNICATION_SIGNALLING_ATTRIBUTES)));

        assertWithMessage(
                "Invalid audio attributes exception for voice comms signalling usage").that(
                thrown).hasMessageThat().contains(
                "does not support usage VOICE_COMMUNICATION_SIGNALLING");
    }

    @Test
    public void setEnforceableAttributes_withValidAttributesInSilenceList() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));

        assertWithMessage("Enforceable audio attributes")
                .that(mCarAudioFocusEnforcement.getEnforceableAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setEnforceableAttributes_withDuplicateAttributesInSilenceList() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES, MEDIA_ATTRIBUTES));

        assertWithMessage("Enforceable audio attributes with duplicates")
                .that(mCarAudioFocusEnforcement.getEnforceableAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setEnforceableAttributes_withEmptyAttributesInSilenceList() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of());

        assertWithMessage("Enforceable audio attributes with empty list")
                .that(mCarAudioFocusEnforcement.getEnforceableAttributes()).isEmpty();
    }

    @Test
    public void setDoNotSilenceAttributes_withNullList_throws() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mCarAudioFocusEnforcement.setDoNotSilenceAttributes(/*
                doNotSilenceAttributes= */
                        null));

        assertWithMessage("Null do not silence audio attributes exception").that(
                thrown).hasMessageThat().contains("Do not silence audio attributes");
    }

    @Test
    public void setDoNotSilenceAttributes_withDuplicateAttributesInDoNotSilenceList() {
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES,
                        MEDIA_ATTRIBUTES));

        assertWithMessage("Do not silence audio attributes with duplicates")
                .that(mCarAudioFocusEnforcement.getDoNotSilenceAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void setDoNotSilenceAttributes_withEmptyAttributesInDoNotSilenceList() {
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(List.of());

        assertWithMessage("Do not silence audio attributes with empty list")
                .that(mCarAudioFocusEnforcement.getDoNotSilenceAttributes()).isEmpty();
    }

    @Test
    public void setDoNotSilenceAttributes_withValidAttributesInDoNotSilenceList() {
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));

        assertWithMessage("Do not silence audio attributes with empty list")
                .that(mCarAudioFocusEnforcement.getDoNotSilenceAttributes())
                .containsExactly(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES);
    }

    @Test
    public void onFocusChange_withGainedFocus_unsilencesPlayback() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);
        SparseArray<List<AudioFocusInfo>> focusHolders = createFocusHolderMap(mediaFocusInfo);
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        mCarAudioFocusEnforcement.onFocusChange(focusHolders);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 2);
        assertWithMessage("Player volume with focus changes")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f)
                .inOrder();
    }

    @Test
    public void onFocusChange_withNullFocusHolders_throws() {
        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> mCarAudioFocusEnforcement.onFocusChange(null));

        assertWithMessage("Null focus holders exception")
                .that(thrown)
                .hasMessageThat()
                .contains("Focus holders by zone id's");
    }

    @Test
    public void onFocusChange_withRelaxedParkedModeAndCriticalFocusChanges_unsilences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        AudioFocusInfo emergencyFocusInfo = createMockFocusInfo(USAGE_EMERGENCY, TEST_OTHER_UID);
        SparseArray<List<AudioFocusInfo>> focusHoldersWithEmergency =
                createFocusHolderMap(emergencyFocusInfo);
        mCarAudioFocusEnforcement.onFocusChange(focusHoldersWithEmergency);
        SparseArray<List<AudioFocusInfo>> emptyFocusHolders = createFocusHolderMap();

        mCarAudioFocusEnforcement.onFocusChange(emptyFocusHolders);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 2);
        assertWithMessage("Player volume during critical focus lifecycle in relaxed mode")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f)
                .inOrder();
    }

    @Test
    public void onFocusChange_withMultipleZones_onlyActsOnPrimaryZone() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);
        SparseArray<List<AudioFocusInfo>> focusHolders = createFocusHolderMap(mediaFocusInfo);
        int otherZoneId = PRIMARY_AUDIO_ZONE + 1;
        focusHolders.put(otherZoneId, List.of(createMockFocusInfo(USAGE_GAME, TEST_OTHER_UID)));

        mCarAudioFocusEnforcement.onFocusChange(focusHolders);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 2);
        assertWithMessage("Player volume with multiple zones")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f)
                .inOrder();
    }

    @Test
    public void onAudioPlaybackChange_withFocusHolder_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);
        SparseArray<List<AudioFocusInfo>> focusHolders = createFocusHolderMap(mediaFocusInfo);
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onFocusChange(focusHolders);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 0);
        assertWithMessage("Player volume on matching focus usage")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withMismatchedFocusHolderAttributes_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_GAME, TEST_UID);
        SparseArray<List<AudioFocusInfo>> focusHolders = createFocusHolderMap(mediaFocusInfo);
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onFocusChange(focusHolders);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume on non-matching focus usage")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_withMismatchedFocusHolderUid_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);
        SparseArray<List<AudioFocusInfo>> focusHolders = createFocusHolderMap(mediaFocusInfo);
        AudioPlaybackConfiguration mediaConfig =
                createMockPlaybackConfig(MEDIA_ATTRIBUTES, TEST_OTHER_UID);
        mCarAudioFocusEnforcement.onFocusChange(focusHolders);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume on mismatched uid")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_withoutFocusHolder_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume with no focus holder")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_forUnenforceableUsage_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig =
                createMockPlaybackConfig(ASSISTANT_ATTRIBUTES, TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 0);
        assertWithMessage("Player volume with no focus holder and exempt usage")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withNullPlayerProxy_doesNotThrow() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        when(mediaConfig.getPlayerProxy()).thenReturn(null);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        verify(mediaConfig).getPlayerProxy();
    }

    @Test
    public void onAudioPlaybackChange_whenSetVolumeFails_revertsSilencedState() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        PlayerProxy playerProxy = mediaConfig.getPlayerProxy();
        doThrow(new RuntimeException("Binder call failed")).when(playerProxy).setVolume(anyFloat());
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume after failed binder call")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_withTwoPlayersOneFocus_silencesOne() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, GAME_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        AudioPlaybackConfiguration gameConfig = createMockPlaybackConfig(GAME_ATTRIBUTES,
                TEST_OTHER_UID);
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);
        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap(mediaFocusInfo));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig, gameConfig));

        ArgumentCaptor<Float> mediaVolume = captureVolumeChanged(mediaConfig, 0);
        ArgumentCaptor<Float> gameVolume = captureVolumeChanged(gameConfig, 1);
        assertWithMessage("Media player volume").that(mediaVolume.getAllValues()).isEmpty();
        assertWithMessage("Game player volume").that(gameVolume.getValue()).isEqualTo(0.0f);
    }

    @Test
    public void onFocusChange_withCallback_isNotifiedOfSilence()
            throws RemoteException, InterruptedException {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        TestEnforceableAudioFocusCallback callback = new TestEnforceableAudioFocusCallback(1);
        mCarAudioFocusEnforcement.registerCallback(callback);
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        callback.waitForCallback();

        List<EnforcedAudioFocusInfo> infos = callback.getEnforcedInfos();
        assertWithMessage("Enforced audio focus info count").that(infos).hasSize(1);
        EnforcedAudioFocusInfo info = infos.get(0);
        assertWithMessage("Enforced audio focus info UID").that(info.getUid())
                .isEqualTo(TEST_UID);
        assertWithMessage("Enforced audio focus info attributes")
                .that(info.getAudioAttributes()).isEqualTo(MEDIA_ATTRIBUTES);
        assertWithMessage("Enforced audio focus info silenced status")
                .that(info.isSilenced()).isTrue();
    }

    @Test
    public void onFocusChange_withCallback_isNotifiedOfUnsilence()
            throws RemoteException, InterruptedException {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        TestEnforceableAudioFocusCallback callback = new TestEnforceableAudioFocusCallback(2);
        mCarAudioFocusEnforcement.registerCallback(callback);
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_UID);

        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap(mediaFocusInfo));
        callback.waitForCallback();

        List<EnforcedAudioFocusInfo> infos = callback.getEnforcedInfos();
        assertWithMessage("Enforced audio focus info count").that(infos).hasSize(2);
        EnforcedAudioFocusInfo info = infos.get(1);
        assertWithMessage("Enforced audio focus info UID").that(info.getUid())
                .isEqualTo(TEST_UID);
        assertWithMessage("Enforced audio focus info attributes")
                .that(info.getAudioAttributes()).isEqualTo(MEDIA_ATTRIBUTES);
        assertWithMessage("Enforced audio focus info silenced status")
                .that(info.isSilenced()).isFalse();
    }

    @Test
    public void release_killsCallbacks() throws RemoteException {
        TestEnforceableAudioFocusCallback callback = new TestEnforceableAudioFocusCallback(0);
        mCarAudioFocusEnforcement.registerCallback(callback);

        mCarAudioFocusEnforcement.release();

        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        assertWithMessage("Enforced audio focus info after release")
                .that(callback.getEnforcedInfos()).isEmpty();
    }

    @Test
    public void registerCallback_withNullCallback_returnsFalse() {
        assertWithMessage("Register null callback result")
                .that(mCarAudioFocusEnforcement.registerCallback(null)).isFalse();
    }

    @Test
    public void unregisterCallback_withNullCallback_returnsFalse() {
        assertWithMessage("Unregister null callback result")
                .that(mCarAudioFocusEnforcement.unregisterCallback(null)).isFalse();
    }

    @Test
    public void onAudioPlaybackChange_withNullActivePlayback_throws() {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mCarAudioFocusEnforcement.onAudioPlaybackChange(null));

        assertWithMessage("Null active playback exception")
                .that(thrown)
                .hasMessageThat()
                .contains("Active playbacks by zone id's");
    }

    @Test
    public void onAudioPlaybackChange_whenInDoNotSilenceList_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(List.of(ASSISTANT_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig =
                createMockPlaybackConfig(ASSISTANT_ATTRIBUTES, TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 0);
        assertWithMessage("Player volume with no focus holder and do not silence list")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_whenNotInDoNotSilenceListWithPartialMatch_silences() {
        AudioAttributes partialAssistant =
                new AudioAttributes.Builder()
                        .setUsage(USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
        mCarAudioFocusEnforcement.setEnforceableAttributes(
                List.of(MEDIA_ATTRIBUTES, ASSISTANT_ATTRIBUTES));
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(List.of(partialAssistant));
        AudioPlaybackConfiguration mediaConfig =
                createMockPlaybackConfig(ASSISTANT_ATTRIBUTES, TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage(
                "Player volume with no focus holder and partial match do not silence list")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_whenTagsDoNotMatchDoNotSilenceList_silences() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder().setUsage(USAGE_MEDIA);
        AudioManagerHelper.addTagToAudioAttributes(builder, "tag1");
        AudioAttributes mediaWithTag = builder.build();
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(List.of(mediaWithTag));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage(
                "Player volume with no focus and no matching tags in do not silence list")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void onAudioPlaybackChange_whenTagsMatchDoNotSilenceList_doesNotSilence() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder().setUsage(USAGE_MEDIA);
        AudioManagerHelper.addTagToAudioAttributes(builder, "tag1");
        AudioAttributes mediaWithTag = builder.build();
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        mCarAudioFocusEnforcement.setDoNotSilenceAttributes(List.of(mediaWithTag));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(mediaWithTag, TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 0);
        assertWithMessage(
                "Player volume with no focus and matching tags in do not silence list")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_forNonPrimaryZone_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks = new SparseArray<>();
        int nonPrimaryZoneId = PRIMARY_AUDIO_ZONE + 1;
        activePlaybacks.put(nonPrimaryZoneId, List.of(mediaConfig));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 0);
        assertWithMessage("Player volume for non-primary zone")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withCriticalAudioInRelaxedMode_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration emergencyConfig =
                createMockPlaybackConfig(EMERGENCY_ATTRIBUTES, TEST_UID);
        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(emergencyConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(emergencyConfig, 0);
        assertWithMessage("Critical audio volume in relaxed mode")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withCriticalAudioAndOtherCriticalFocus_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration emergencyConfig =
                createMockPlaybackConfig(EMERGENCY_ATTRIBUTES, TEST_UID);
        AudioFocusInfo safetyFocusInfo = createMockFocusInfo(USAGE_SAFETY, TEST_OTHER_UID);
        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap(safetyFocusInfo));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(emergencyConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(emergencyConfig, 0);
        assertWithMessage("Critical audio volume with other critical focus")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withCriticalAudioAndNonCriticalFocus_doesNotSilence() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration emergencyConfig =
                createMockPlaybackConfig(EMERGENCY_ATTRIBUTES, TEST_UID);
        AudioFocusInfo mediaFocusInfo = createMockFocusInfo(USAGE_MEDIA, TEST_OTHER_UID);
        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap(mediaFocusInfo));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(emergencyConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(emergencyConfig, 0);
        assertWithMessage("Critical audio volume with non-critical focus")
                .that(volumeCaptor.getAllValues())
                .isEmpty();
    }

    @Test
    public void onAudioPlaybackChange_withMultipleZones_onlyActsOnPrimaryZone() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        AudioPlaybackConfiguration gameConfig = createMockPlaybackConfig(GAME_ATTRIBUTES,
                TEST_OTHER_UID);
        SparseArray<List<AudioPlaybackConfiguration>> activePlaybacks =
                createActivePlaybackConfigsMap(mediaConfig);
        int otherZoneId = PRIMARY_AUDIO_ZONE + 1;
        activePlaybacks.put(otherZoneId, List.of(gameConfig));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(activePlaybacks);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume with multiple zones")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void enableRelaxedParkMode_whileSilenced_unsilences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 2);
        assertWithMessage("Player volume after entering parked mode")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f)
                .inOrder();
    }

    @Test
    public void enableRelaxedParkMode_whileUnsilencedInRelaxedParkMode_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);

        mCarAudioFocusEnforcement.enableRelaxedParkMode(false);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 3);
        assertWithMessage("Player volume after exiting parked mode")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f, 0.0f)
                .inOrder();
    }

    @Test
    public void enableRelaxedParkMode_withNoChangeInState_doesNothing() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));
        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);

        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 2);
        assertWithMessage("Player volume with no change in relaxed mode")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f, 1.0f)
                .inOrder();
    }

    @Test
    public void evaluateAndEnforceFocusState_withRelaxedModeAndCriticalRequest_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.enableRelaxedParkMode(true);
        AudioFocusInfo emergencyFocusInfo = createMockFocusInfo(USAGE_EMERGENCY, TEST_OTHER_UID);
        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap(emergencyFocusInfo));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume in relaxed mode with critical request")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    @Test
    public void enforceAudioFocus_withNoChangeInState_doesNotSetVolume() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume with no change in state")
                .that(volumeCaptor.getAllValues())
                .containsExactly(0.0f);
    }

    @Test
    public void shouldSilence_withNullFocusInfos_silences() {
        mCarAudioFocusEnforcement.setEnforceableAttributes(List.of(MEDIA_ATTRIBUTES));
        AudioPlaybackConfiguration mediaConfig = createMockPlaybackConfig(MEDIA_ATTRIBUTES,
                TEST_UID);

        mCarAudioFocusEnforcement.onFocusChange(createFocusHolderMap());
        mCarAudioFocusEnforcement.onAudioPlaybackChange(
                createActivePlaybackConfigsMap(mediaConfig));

        ArgumentCaptor<Float> volumeCaptor = captureVolumeChanged(mediaConfig, 1);
        assertWithMessage("Player volume with null focus infos")
                .that(volumeCaptor.getValue())
                .isEqualTo(0.0f);
    }

    private static ArgumentCaptor<Float> captureVolumeChanged(
            AudioPlaybackConfiguration mediaConfig, int count) {
        ArgumentCaptor<Float> volumeCaptor = ArgumentCaptor.forClass(Float.class);
        PlayerProxy playerProxy = mediaConfig.getPlayerProxy();
        verify(playerProxy, times(count)).setVolume(volumeCaptor.capture());
        return volumeCaptor;
    }

    private static AudioPlaybackConfiguration createMockPlaybackConfig(
            AudioAttributes attributes, int uid) {
        PlayerProxy playerProxy = mock(PlayerProxy.class);
        AudioPlaybackConfiguration config = mock(AudioPlaybackConfiguration.class);
        when(config.getAudioAttributes()).thenReturn(attributes);
        when(config.getClientUid()).thenReturn(uid);
        when(config.getPlayerProxy()).thenReturn(playerProxy);
        return config;
    }

    private static SparseArray<List<AudioPlaybackConfiguration>> createActivePlaybackConfigsMap(
            AudioPlaybackConfiguration... configs) {
        SparseArray<List<AudioPlaybackConfiguration>> array = new SparseArray<>();
        array.put(PRIMARY_AUDIO_ZONE, List.of(configs));
        return array;
    }

    private static SparseArray<List<AudioFocusInfo>> createFocusHolderMap(
            AudioFocusInfo... configs) {
        SparseArray<List<AudioFocusInfo>> array = new SparseArray<>();
        array.put(PRIMARY_AUDIO_ZONE, List.of(configs));
        return array;
    }

    private AudioFocusInfo createMockFocusInfo(int usage, int uid) {
        return new AudioFocusInfoBuilder().setUsage(usage).setClientUid(uid)
                .setClientId("clientId").setPackageName("test.package")
                .setGainRequest(AudioManager.AUDIOFOCUS_GAIN).createAudioFocusInfo();
    }

    private static final class TestEnforceableAudioFocusCallback
            extends IEnforceableAudioFocusCallback.Stub {
        private final List<EnforcedAudioFocusInfo> mEnforcedAudioFocusInfos = new ArrayList<>();
        private CountDownLatch mLatch;

        TestEnforceableAudioFocusCallback(int count) {
            mLatch = new CountDownLatch(count);
        }

        @Override
        public void onEnforcedAudioFocusChanged(List<EnforcedAudioFocusInfo> infos) {
            mEnforcedAudioFocusInfos.addAll(infos);
            mLatch.countDown();
        }

        List<EnforcedAudioFocusInfo> getEnforcedInfos() {
            return new ArrayList<>(mEnforcedAudioFocusInfos);
        }

        void waitForCallback() throws InterruptedException {
            mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }
}
