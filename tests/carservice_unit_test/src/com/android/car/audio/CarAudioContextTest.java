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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VIRTUAL_SOURCE;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;

import static com.android.car.audio.CarAudioContext.isCriticalAudioContext;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.car.builtin.media.AudioManagerHelper;
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
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class CarAudioContextTest {

    private static final int INVALID_CONTEXT_ID = 0;
    private static final int INVALID_CONTEXT = -5;

    private static final AudioAttributes UNKNOWN_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_UNKNOWN).build();
    private static final AudioAttributes MEDIA_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final AudioAttributes GAME_USAGE_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(USAGE_GAME).build();

    private @CarAudioContext.AudioContext int mMediaAudioContext =
            CarAudioContext.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA));
    private @CarAudioContext.AudioContext int mAlarmAudioContext =
            CarAudioContext.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_ALARM));
    private @CarAudioContext.AudioContext int mCallAudioContext =
            CarAudioContext.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION));
    private @CarAudioContext.AudioContext int mCallRingAudioContext =
            CarAudioContext.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_NOTIFICATION_RINGTONE));
    private @CarAudioContext.AudioContext int mEmergencyAudioContext =
            CarAudioContext.getContextForAudioAttribute(
                    CarAudioContext.getAudioAttributeFromUsage(USAGE_EMERGENCY));
    private @CarAudioContext.AudioContext int mNavigationAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE));
    private @CarAudioContext.AudioContext int mNotificationAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_NOTIFICATION));
    private @CarAudioContext.AudioContext int mAnnouncementAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT));
    private @CarAudioContext.AudioContext int mSafetyAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_SAFETY));
    private @CarAudioContext.AudioContext int mSystemSoundAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ASSISTANCE_SONIFICATION));
    private @CarAudioContext.AudioContext int mVehicleStatusAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_VEHICLE_STATUS));
    private @CarAudioContext.AudioContext int mAssistantAudioContext =
            CarAudioContext.getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ASSISTANT));

    @Test
    public void getContextForAudioAttributes_forAttributeWithValidUsage_returnsContext() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();

        assertWithMessage("Context for valid audio attributes usage")
                .that(CarAudioContext.getContextForAttributes(attributes))
                .isEqualTo(mMediaAudioContext);
    }

    @Test
    public void getContextForAudioAttributes_forAttributesWithInvalidUsage_returnsInvalidContext() {
        AudioAttributes attributes =
                new AudioAttributes.Builder().setUsage(USAGE_VIRTUAL_SOURCE).build();

        assertWithMessage("Context for invalid audio attribute")
                .that(CarAudioContext.getContextForAttributes(attributes))
                .isEqualTo(CarAudioContext.getInvalidContext());
    }

    @Test
    public void getAudioAttributesForContext_withValidContext_returnsAttributes() {
        AudioAttributes[] attributes =
                CarAudioContext.getAudioAttributesForContext(mMediaAudioContext);
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
        Set<CarAudioContext.AudioAttributesWrapper> allUsages = new ArraySet<>();
        for (@AudioContext int audioContext : CarAudioContext.getAllContextsIds()) {
            AudioAttributes[] audioAttributes =
                    CarAudioContext.getAudioAttributesForContext(audioContext);
            List<CarAudioContext.AudioAttributesWrapper> attributesWrappers =
                    Arrays.stream(audioAttributes).map(CarAudioContext.AudioAttributesWrapper::new)
                            .collect(Collectors.toList());

            assertWithMessage("Unique audio attributes wrapper for context %s", audioContext)
                    .that(allUsages.addAll(attributesWrappers)).isTrue();
        }
    }

    @Test
    public void getUniqueContextsForAudioAttribute_withEmptyArray_returnsEmptySet() {
        Set<Integer> result =
                CarAudioContext.getUniqueContextsForAudioAttributes(new ArrayList<>());

        assertWithMessage("Empty unique context list").that(result).isEmpty();
    }

    @Test
    public void getUniqueContextsForAudioAttribute_withInvalidElement_returnsEmptySet() {
        Set<Integer> result =
                CarAudioContext.getUniqueContextsForAudioAttributes(
                        new ArrayList<>(CarAudioContext
                                .getContextForAudioAttribute(CarAudioContext
                                        .getAudioAttributeFromUsage(AudioManagerHelper
                                                .getUsageVirtualSource()))));

        assertWithMessage("Empty unique context list for invalid context")
                .that(result).isEmpty();
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
                .that(result).containsExactly(mMediaAudioContext);
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
                .that(result).containsExactly(mMediaAudioContext, mNavigationAudioContext,
                        mEmergencyAudioContext);
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
                .that(contexts).containsExactly(mMediaAudioContext, mNotificationAudioContext);
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
                .that(contexts).containsExactly(mMediaAudioContext, mSafetyAudioContext,
                        mEmergencyAudioContext);
    }

    @Test
    public void getUniqueAttributesHoldingFocus_withInvalidAttribute_returnsEmpty() {
        List<AudioAttributes> audioAttributes = new ArrayList<>(/* initialCapacity= */ 1);
        audioAttributes.add(CarAudioContext
                .getAudioAttributeFromUsage(AudioManagerHelper.getUsageVirtualSource()));

        Set<Integer> contexts =
                CarAudioContext.getUniqueContextsForAudioAttributes(audioAttributes);

        assertWithMessage("Unique contexts without invalid")
                .that(contexts).isEmpty();
    }

    @Test
    public void isCriticalAudioContext_forNonCriticalContexts_returnsFalse() {
        assertWithMessage("Non-critical context INVALID")
                .that(isCriticalAudioContext(CarAudioContext.getInvalidContext())).isFalse();
        assertWithMessage("Non-critical context MUSIC")
                .that(isCriticalAudioContext(mMediaAudioContext)).isFalse();
        assertWithMessage("Non-critical context NAVIGATION")
                .that(isCriticalAudioContext(mNavigationAudioContext)).isFalse();
        assertWithMessage("Non-critical context VOICE_COMMAND")
                .that(isCriticalAudioContext(mAssistantAudioContext)).isFalse();
        assertWithMessage("Non-critical context CALL_RING")
                .that(isCriticalAudioContext(mCallRingAudioContext)).isFalse();
        assertWithMessage("Non-critical context CALL")
                .that(isCriticalAudioContext(mCallAudioContext)).isFalse();
        assertWithMessage("Non-critical context ALARM")
                .that(isCriticalAudioContext(mAlarmAudioContext)).isFalse();
        assertWithMessage("Non-critical context NOTIFICATION")
                .that(isCriticalAudioContext(mNotificationAudioContext)).isFalse();
        assertWithMessage("Non-critical context SYSTEM_SOUND")
                .that(isCriticalAudioContext(mSystemSoundAudioContext)).isFalse();
        assertWithMessage("Non-critical context VEHICLE_STATUS")
                .that(isCriticalAudioContext(mVehicleStatusAudioContext)).isFalse();
        assertWithMessage("Non-critical context ANNOUNCEMENT")
                .that(isCriticalAudioContext(mAnnouncementAudioContext)).isFalse();
    }

    @Test
    public void isCriticalAudioContext_forCriticalContexts_returnsTrue() {
        assertWithMessage("Critical context EMERGENCY")
                .that(isCriticalAudioContext(mEmergencyAudioContext)).isTrue();
        assertWithMessage("Critical context SAFETY")
                .that(isCriticalAudioContext(mSafetyAudioContext)).isTrue();
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
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(mCallAudioContext);

        assertWithMessage("Is call check")
                .that(isRingerOrCall).isTrue();
    }

    @Test
    public void isRingerOrCallContext_withRingerContext_returnsTrue() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(mCallRingAudioContext);

        assertWithMessage("Is ringer check")
                .that(isRingerOrCall).isTrue();
    }

    @Test
    public void isRingerOrCallContext_withNonCriticalContext_returnsFalse() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(mMediaAudioContext);

        assertWithMessage("Non critical context is ringer or call check")
                .that(isRingerOrCall).isFalse();
    }

    @Test
    public void isRingerOrCallContext_withCriticalContext_returnsFalse() {
        boolean isRingerOrCall = CarAudioContext.isRingerOrCallContext(mEmergencyAudioContext);

        assertWithMessage("Critical context is ringer or call check")
                .that(isRingerOrCall).isFalse();
    }

    @Test
    public void preconditionCheckAudioContext_withNonExistentContext_throws() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            CarAudioContext.preconditionCheckAudioContext(-mEmergencyAudioContext);
        });

        assertWithMessage("Precondition exception with non existent context check")
                .that(thrown).hasMessageThat()
                .contains("Car audio context " + -mEmergencyAudioContext + " is invalid");
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
                .that(CarAudioContext.toString(CarAudioContext.getInvalidContext()))
                .isEqualTo("INVALID");
        assertWithMessage("Context String for MUSIC")
                .that(CarAudioContext.toString(mMediaAudioContext)).isEqualTo("MUSIC");
        assertWithMessage("Context String for NAVIGATION")
                .that(CarAudioContext.toString(mNavigationAudioContext)).isEqualTo("NAVIGATION");
        assertWithMessage("Context String for VOICE_COMMAND")
                .that(CarAudioContext.toString(mAssistantAudioContext))
                .isEqualTo("VOICE_COMMAND");
        assertWithMessage("Context String for CALL_RING")
                .that(CarAudioContext.toString(mCallRingAudioContext)).isEqualTo("CALL_RING");
        assertWithMessage("Context String for CALL")
                .that(CarAudioContext.toString(mCallAudioContext)).isEqualTo("CALL");
        assertWithMessage("Context String for ALARM")
                .that(CarAudioContext.toString(mAlarmAudioContext)).isEqualTo("ALARM");
        assertWithMessage("Context String for NOTIFICATION")
                .that(CarAudioContext.toString(mNotificationAudioContext))
                .isEqualTo("NOTIFICATION");
    }

    @Test
    public void toString_forSystemSoundsContexts_returnsStrings() {
        assertWithMessage("Context String for SYSTEM_SOUND")
                .that(CarAudioContext.toString(mSystemSoundAudioContext))
                .isEqualTo("SYSTEM_SOUND");
        assertWithMessage("Context String for EMERGENCY")
                .that(CarAudioContext.toString(mEmergencyAudioContext)).isEqualTo("EMERGENCY");
        assertWithMessage("Context String for SAFETY")
                .that(CarAudioContext.toString(mSafetyAudioContext)).isEqualTo("SAFETY");
        assertWithMessage("Context String for VEHICLE_STATUS")
                .that(CarAudioContext.toString(mVehicleStatusAudioContext))
                .isEqualTo("VEHICLE_STATUS");
        assertWithMessage("Context String for ANNOUNCEMENT")
                .that(CarAudioContext.toString(mAnnouncementAudioContext))
                .isEqualTo("ANNOUNCEMENT");
    }

    @Test
    public void toString_forInvalidContext_returnsUnsupportedContext() {
        assertWithMessage("Context String for invalid context")
                .that(CarAudioContext.toString(/* context= */ -1))
                .contains("Unsupported Context");
    }

    @Test
    public void getAllContextIds_returnsAllContext() {
        assertWithMessage("All context IDs")
                .that(CarAudioContext.getAllContextsIds())
                .containsExactly(mMediaAudioContext,
                        mNavigationAudioContext,
                        mAssistantAudioContext,
                        mCallRingAudioContext,
                        mCallAudioContext,
                        mAlarmAudioContext,
                        mNotificationAudioContext,
                        mSystemSoundAudioContext,
                        mEmergencyAudioContext,
                        mSafetyAudioContext,
                        mVehicleStatusAudioContext,
                        mAnnouncementAudioContext);
    }

    @Test
    public void getAllContextIds_failsForInvalid() {
        assertWithMessage("All context IDs")
                .that(CarAudioContext.getAllContextsIds())
                .doesNotContain(CarAudioContext.getInvalidContext());
    }

    @Test
    public void getCarSystemContextIds() {
        List<Integer> systemContextIds = CarAudioContext.getCarSystemContextIds();

        assertWithMessage("Car audio system contexts")
                .that(systemContextIds)
                .containsExactly(mEmergencyAudioContext, mSafetyAudioContext,
                        mVehicleStatusAudioContext, mAnnouncementAudioContext);
    }

    @Test
    public void getNonCarSystemContextIds() {
        List<Integer> nonCarSystemContextIds = CarAudioContext.getNonCarSystemContextIds();

        assertWithMessage("Car audio non system contexts")
                .that(nonCarSystemContextIds)
                .containsExactly(mMediaAudioContext, mNavigationAudioContext,
                        mAssistantAudioContext, mCallRingAudioContext, mCallAudioContext,
                        mAlarmAudioContext, mNotificationAudioContext, mSystemSoundAudioContext);
    }

    @Test
    public void validateAllAudioAttributesSupported() {
        Set<Integer> allContext = new ArraySet<>(CarAudioContext.getAllContextsIds());

        boolean valid = CarAudioContext.validateAllAudioAttributesSupported(allContext);

        assertWithMessage("All audio attributes are supported flag")
                .that(valid).isTrue();
    }

    @Test
    public void validateAllAudioAttributesSupported_forNonCarSystemContextsOnly_fails() {
        Set<Integer> allContext = new ArraySet<>(CarAudioContext.getNonCarSystemContextIds());

        boolean valid = CarAudioContext.validateAllAudioAttributesSupported(allContext);

        assertWithMessage("Missing car audio system audio attributes are supported flag")
                .that(valid).isFalse();
    }

    @Test
    public void validateAllAudioAttributesSupported_forCarSystemContextsOnly_fails() {
        Set<Integer> allContext = new ArraySet<>(CarAudioContext.getNonCarSystemContextIds());

        boolean valid = CarAudioContext.validateAllAudioAttributesSupported(allContext);

        assertWithMessage("Missing non car audio system audio attributes are supported flag")
                .that(valid).isFalse();
    }

    @Test
    public void getAllContextsInfo() {
        Set<Integer> allContextIds =
                new ArraySet<Integer>(CarAudioContext.getAllContextsIds());
        allContextIds.add(CarAudioContext.getInvalidContext());

        List<CarAudioContextInfo> contextInfos = CarAudioContext.getAllContextsInfo();

        for (CarAudioContextInfo info : contextInfos) {
            assertWithMessage("Context info id for %s", info)
                    .that(info.getId()).isIn(allContextIds);
        }
    }

    @Test
    public void getAllContextsInfo_sameSizeAsGetAllContextsIds() {
        Set<Integer> allContextIds =
                new ArraySet<Integer>(CarAudioContext.getAllContextsIds());
        allContextIds.add(CarAudioContext.getInvalidContext());

        List<CarAudioContextInfo> contextInfos = CarAudioContext.getAllContextsInfo();

        assertWithMessage("All contexts info size")
                .that(contextInfos.size()).isEqualTo(allContextIds.size());
    }

    @Test
    public void getInvalidContext() {
        assertWithMessage("Invalid context id")
                .that(CarAudioContext.getInvalidContext()).isEqualTo(INVALID_CONTEXT_ID);
    }

    @Test
    public void isInvalidContext() {
        assertWithMessage("Is invalid context id")
                .that(CarAudioContext.isInvalidContextId(INVALID_CONTEXT_ID)).isTrue();
    }
}
