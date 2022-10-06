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

import android.annotation.IntDef;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.util.Slogf;
import android.media.AudioAttributes;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.annotation.AttributeUsage;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groupings of {@link AttributeUsage}s to simplify configuration of car audio routing, volume
 * groups, and focus interactions for similar usages.
 */
public final class CarAudioContext {

    private static final String TAG = CarLog.tagFor(CarAudioContext.class);

    /*
     * Shouldn't be used
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.INVALID
     */
    static final int INVALID = 0;
    /*
     * Music playback
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.INVALID implicitly + 1
     */
    static final int MUSIC = 1;
    /*
     * Navigation directions
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.MUSIC implicitly + 1
     */
    static final int NAVIGATION = 2;
    /*
     * Voice command session
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.NAVIGATION implicitly + 1
     */
    static final int VOICE_COMMAND = 3;
    /*
     * Voice call ringing
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber
     *     .VOICE_COMMAND implicitly + 1
     */
    static final int CALL_RING = 4;
    /*
     * Voice call
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.CALL_RING implicitly + 1
     */
    static final int CALL = 5;
    /*
     * Alarm sound from Android
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.CALL implicitly + 1
     */
    static final int ALARM = 6;
    /*
     * Notifications
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber.ALARM implicitly + 1
     */
    static final int NOTIFICATION = 7;
    /*
     * System sounds
     * ::android::hardware::automotive::audiocontrol::V1_0::ContextNumber
     *     .NOTIFICATION implicitly + 1
     */
    static final int SYSTEM_SOUND = 8;
    /*
     * Emergency related sounds such as collision warnings
     */
    static final int EMERGENCY = 9;
    /*
     * Safety sounds such as obstacle detection when backing up or when changing lanes
     */
    static final int SAFETY = 10;
    /*
     * Vehicle Status related sounds such as check engine light or seat belt chimes
     */
    static final int VEHICLE_STATUS = 11;
    /*
     * Announcement such as traffic announcements
     */
    static final int ANNOUNCEMENT = 12;

    @IntDef({
            INVALID,
            MUSIC,
            NAVIGATION,
            VOICE_COMMAND,
            CALL_RING,
            CALL,
            ALARM,
            NOTIFICATION,
            SYSTEM_SOUND,
            EMERGENCY,
            SAFETY,
            VEHICLE_STATUS,
            ANNOUNCEMENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioContext {
    }

    static final int[] CONTEXTS = {
            // The items are in a sorted order
            // Starting at one
            MUSIC,
            NAVIGATION,
            VOICE_COMMAND,
            CALL_RING,
            CALL,
            ALARM,
            NOTIFICATION,
            SYSTEM_SOUND,
            EMERGENCY,
            SAFETY,
            VEHICLE_STATUS,
            ANNOUNCEMENT
    };

    private static final AudioAttributes[] SYSTEM_ATTRIBUTES = new AudioAttributes[] {
            getAudioAttributeFromUsage(AudioAttributes.USAGE_CALL_ASSISTANT),
            getAudioAttributeFromUsage(AudioAttributes.USAGE_EMERGENCY),
            getAudioAttributeFromUsage(AudioAttributes.USAGE_SAFETY),
            getAudioAttributeFromUsage(AudioAttributes.USAGE_VEHICLE_STATUS),
            getAudioAttributeFromUsage(AudioAttributes.USAGE_ANNOUNCEMENT)
    };

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_MUSIC =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_UNKNOWN),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_GAME),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)
            }, "MUSIC", MUSIC);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_NAVIGATION =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes
                    .USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            }, "NAVIGATION", NAVIGATION);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_VOICE_COMMAND =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANT)
            }, "VOICE_COMMAND", VOICE_COMMAND);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_CALL_RING =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            }, "CALL_RING", CALL_RING);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_CALL =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_CALL_ASSISTANT),
                    getAudioAttributeFromUsage(AudioAttributes
                            .USAGE_VOICE_COMMUNICATION_SIGNALLING),
            }, "CALL", CALL);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_ALARM =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ALARM)
            }, "ALARM", ALARM);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_NOTIFICATION =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            }, "NOTIFICATION", NOTIFICATION);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_SYSTEM_SOUND =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            }, "SYSTEM_SOUND", SYSTEM_SOUND);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_EMERGENCY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_EMERGENCY)
            }, "EMERGENCY", EMERGENCY);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_SAFETY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_SAFETY)
            }, "SAFETY", SAFETY);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_VEHICLE_STATUS =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_VEHICLE_STATUS)
            }, "VEHICLE_STATUS", VEHICLE_STATUS);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_ANNOUNCEMENT =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ANNOUNCEMENT)
            }, "ANNOUNCEMENT", ANNOUNCEMENT);

    private static final CarAudioContextInfo CAR_CONTEXT_INFO_INVALID =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioManagerHelper.getUsageVirtualSource())
            }, "INVALID", INVALID);

    private static final CarAudioContextInfo[] CAR_CONTEXT_INFO = {
            CAR_CONTEXT_INFO_MUSIC,
            CAR_CONTEXT_INFO_NAVIGATION,
            CAR_CONTEXT_INFO_VOICE_COMMAND,
            CAR_CONTEXT_INFO_CALL_RING,
            CAR_CONTEXT_INFO_CALL,
            CAR_CONTEXT_INFO_ALARM,
            CAR_CONTEXT_INFO_NOTIFICATION,
            CAR_CONTEXT_INFO_SYSTEM_SOUND,
            CAR_CONTEXT_INFO_EMERGENCY,
            CAR_CONTEXT_INFO_SAFETY,
            CAR_CONTEXT_INFO_VEHICLE_STATUS,
            CAR_CONTEXT_INFO_ANNOUNCEMENT,
            CAR_CONTEXT_INFO_INVALID
    };

    public static int[] getSystemUsages() {
        return covertAttributesToUsage(SYSTEM_ATTRIBUTES);
    }

    private static final SparseArray<String> CONTEXT_NAMES = new SparseArray<>(CONTEXTS.length + 1);
    private static final SparseArray<AudioAttributes[]> CONTEXT_TO_ATTRIBUTES = new SparseArray<>();
    private static final Map<AudioAttributesWrapper, Integer> AUDIO_ATTRIBUTE_TO_CONTEXT =
            new ArrayMap<>();

    static {
        for (int index = 0; index < CAR_CONTEXT_INFO.length; index++) {
            CarAudioContextInfo info = CAR_CONTEXT_INFO[index];
            CONTEXT_NAMES.append(info.getId(), info.getName());
            CONTEXT_TO_ATTRIBUTES.put(info.getId(), info.getAudioAttributes());

            AudioAttributes[] attributes = info.getAudioAttributes();
            for (int attributeIndex = 0; attributeIndex < attributes.length; attributeIndex++) {
                AudioAttributesWrapper attributesWrapper =
                        new AudioAttributesWrapper(attributes[attributeIndex]);
                if (AUDIO_ATTRIBUTE_TO_CONTEXT.containsKey(attributesWrapper)) {
                    int mappedContext = AUDIO_ATTRIBUTE_TO_CONTEXT.get(attributesWrapper);
                    Slogf.wtf(TAG, "%s already mapped to context %s, can not remap to context %s",
                            attributesWrapper, mappedContext, info.getId());
                }
                AUDIO_ATTRIBUTE_TO_CONTEXT.put(attributesWrapper, info.getId());
            }
        }
    }

    private CarAudioContext() {
    }

    /**
     * Checks if the audio attribute usage is valid, throws an {@link IllegalArgumentException}
     * if the {@code usage} is not valid.
     *
     * @param usage audio attribute usage to check
     * @throws IllegalArgumentException in case of invalid audio attribute usage
     */
    public static void checkAudioAttributeUsage(@AttributeUsage int usage)
            throws IllegalArgumentException {
        if (isValidAudioAttributeUsage(usage)) {
            return;
        }

        throw new IllegalArgumentException("Invalid audio attribute " + usage);
    }

    /**
     * Determines if the audio attribute usage is valid
     *
     * @param usage audio attribute usage to check
     * @return {@code true} if valid, {@code false} otherwise
     */
    public static boolean isValidAudioAttributeUsage(@AttributeUsage int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_UNKNOWN:
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
            case AudioAttributes.USAGE_ALARM:
            case AudioAttributes.USAGE_NOTIFICATION:
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
            case AudioAttributes.USAGE_NOTIFICATION_EVENT:
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
            case AudioAttributes.USAGE_GAME:
            case AudioAttributes.USAGE_ASSISTANT:
            case AudioAttributes.USAGE_CALL_ASSISTANT:
            case AudioAttributes.USAGE_EMERGENCY:
            case AudioAttributes.USAGE_SAFETY:
            case AudioAttributes.USAGE_VEHICLE_STATUS:
            case AudioAttributes.USAGE_ANNOUNCEMENT:
                return true;
            default:
                // Virtual usage is hidden and thus it must be taken care here.
                return usage == AudioManagerHelper.getUsageVirtualSource();
        }
    }

    /**
     * Checks if the audio context is within the valid range from MUSIC to SYSTEM_SOUND
     */
    static void preconditionCheckAudioContext(@AudioContext int audioContext) {
        Preconditions.checkArgument(Arrays.binarySearch(CONTEXTS, audioContext) >= 0,
                "Car audio context %d is invalid", audioContext);
    }

    static AudioAttributes[] getAudioAttributesForContext(@AudioContext int carAudioContext) {
        preconditionCheckAudioContext(carAudioContext);
        return CONTEXT_TO_ATTRIBUTES.get(carAudioContext);
    }

    static @AudioContext int getContextForAttributes(AudioAttributes attributes) {
        return getContextForAudioAttribute(attributes);
    }

    /**
     * @return Context number for a given audio usage, {@code INVALID} if the given usage is
     * unrecognized.
     */
    static @AudioContext int getContextForAudioAttribute(AudioAttributes attributes) {
        return AUDIO_ATTRIBUTE_TO_CONTEXT.getOrDefault(
                new AudioAttributesWrapper(attributes), INVALID);
    }

    /**
     * Returns an audio attribute for a given usage
     * @param usage input usage, can be an audio attribute system usage
     */
    public static AudioAttributes getAudioAttributeFromUsage(@AttributeUsage int usage) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        if (AudioAttributes.isSystemUsage(usage)) {
            builder.setSystemUsage(usage);
        } else {
            builder.setUsage(usage);
        }
        return builder.build();
    }

    /**
     * Returns an audio attribute wrapper for a given usage
     * @param usage input usage, can be an audio attribute system usage
     */
    public static AudioAttributesWrapper getAudioAttributeWrapperFromUsage(
            @AttributeUsage int usage) {
        return new AudioAttributesWrapper(getAudioAttributeFromUsage(usage));
    }

    static Set<Integer> getUniqueContextsForAudioAttributes(List<AudioAttributes> audioAttributes) {
        Objects.requireNonNull(audioAttributes, "Audio attributes can not be null");
        Set<Integer> uniqueContexts = new ArraySet<>();
        for (int index = 0; index < audioAttributes.size(); index++) {
            uniqueContexts.add(getContextForAudioAttribute(audioAttributes.get(index)));
        }

        return uniqueContexts;
    }

    static boolean isCriticalAudioContext(@CarAudioContext.AudioContext int audioContext) {
        return CarAudioContext.EMERGENCY == audioContext || CarAudioContext.SAFETY == audioContext;
    }

    static boolean isRingerOrCallContext(@CarAudioContext.AudioContext int audioContext) {
        return audioContext == CALL_RING || audioContext == CALL;
    }

    static String toString(@AudioContext int audioContext) {
        String name = CONTEXT_NAMES.get(audioContext);
        if (name != null) {
            return name;
        }
        return "Unsupported Context 0x" + Integer.toHexString(audioContext);
    }

    private static int[] covertAttributesToUsage(AudioAttributes[] audioAttributes) {
        int[] usages = new int[audioAttributes.length];
        for (int index = 0; index < audioAttributes.length; index++) {
            usages[index] = audioAttributes[index].getSystemUsage();
        }
        return usages;
    }

    static List<AudioAttributes> getUniqueAttributesHoldingFocus(
            List<AudioAttributes> audioAttributes) {
        Set<AudioAttributesWrapper> uniqueAudioAttributes = new ArraySet<>();
        List<AudioAttributes> uniqueAttributes = new ArrayList<>(uniqueAudioAttributes.size());
        for (int index = 0; index < audioAttributes.size(); index++) {
            AudioAttributes audioAttribute = audioAttributes.get(index);
            if (uniqueAudioAttributes.contains(new AudioAttributesWrapper(audioAttribute))) {
                continue;
            }
            uniqueAudioAttributes.add(new AudioAttributesWrapper(audioAttributes.get(index)));
            uniqueAttributes.add(new AudioAttributes.Builder(audioAttribute).build());
        }

        return uniqueAttributes;
    }

    /**
     * Class wraps an audio attributes object. This can be used for comparing audio attributes.
     * Current the audio attributes class compares all the attributes in the two objects.
     * In automotive only the audio attribute usage is currently used, thus this class can be used
     * to compare that audio attribute usage.
     */
    public static final class AudioAttributesWrapper {

        private final AudioAttributes mAudioAttributes;

        @VisibleForTesting
        AudioAttributesWrapper(AudioAttributes audioAttributes) {
            mAudioAttributes = audioAttributes;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (object == null || !(object instanceof AudioAttributesWrapper)) {
                return false;
            }

            AudioAttributesWrapper that = (AudioAttributesWrapper) object;

            return mAudioAttributes.getSystemUsage() == that.mAudioAttributes.getSystemUsage();
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(mAudioAttributes.getSystemUsage());
        }

        @Override
        public String toString() {
            return mAudioAttributes.toString();
        }

        /**
         * Returns the audio attributes for the wrapper
         */
        public AudioAttributes getAudioAttributes() {
            return mAudioAttributes;
        }
    }
}
