/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car;

import android.car.media.CarAudioManager;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Holds audio routing policy from config.xml. R.array.audioRoutingPolicy can contain
 * multiple policies and VEHICLE_PROPERTY_AUDIO_HW_VARIANT decide which one to use.
 */
public class AudioRoutingPolicy {

    private final int USAGE_TYPE_INVALID = -1;

    private static final String ROUTING_POLICY_FOR_MOCKED_TEST =
            "0:call,media,radio,unknown#1:nav_guidance,voice_command,alarm,notification,system,safety";

    /** Physical stream to car usages mapping */
    private final int[][] mPhysicalStreamToCarUsages;

    /** Car usage to physical stream mapping */
    private final int[] mCarUsageToPhysicalStream;

    public static AudioRoutingPolicy create(Context context, int policyNumber) {
        final Resources res = context.getResources();
        String[] policies = res.getStringArray(R.array.audioRoutingPolicy);
        String policy;
        if (policyNumber > (policies.length - 1)) {
            Log.e(CarLog.TAG_AUDIO, "AudioRoutingPolicy.create got wrong policy number:" +
                    policyNumber + ", num of available policies:" + policies.length);
            policy = policies[0];
        } else if (policyNumber < 0) { // this is special case for mocked testing.
            policy = ROUTING_POLICY_FOR_MOCKED_TEST;
        } else {
            policy = policies[policyNumber];
        }
        return new AudioRoutingPolicy(policy);
    }

    private static int getCarUsage(String str) {
        switch (str) {
            case "call":
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_CALL;
            case "ringtone":
                return CarAudioManager.CAR_AUDIO_USAGE_RINGTONE;
            case "media":
                return CarAudioManager.CAR_AUDIO_USAGE_MUSIC;
            case "radio":
                return CarAudioManager.CAR_AUDIO_USAGE_RADIO;
            case "nav_guidance":
                return CarAudioManager.CAR_AUDIO_USAGE_NAVIGATION_GUIDANCE;
            case "voice_command":
                return CarAudioManager.CAR_AUDIO_USAGE_VOICE_COMMAND;
            case "alarm":
                return CarAudioManager.CAR_AUDIO_USAGE_ALARM;
            case "notification":
                return CarAudioManager.CAR_AUDIO_USAGE_NOTIFICATION;
            case "system":
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SOUND;
            case "safety":
                return CarAudioManager.CAR_AUDIO_USAGE_SYSTEM_SAFETY_ALERT;
            case "unknown":
                return CarAudioManager.CAR_AUDIO_USAGE_DEFAULT;
        }
        throw new IllegalArgumentException("Wrong audioRoutingPolicy config, unknown car usage:" +
                str);
    }

    private AudioRoutingPolicy(String policy) {
        mCarUsageToPhysicalStream = new int[CarAudioManager.CAR_AUDIO_USAGE_MAX + 1];
        String[] streamPolicies = policy.split("#");
        mPhysicalStreamToCarUsages = new int[streamPolicies.length][];
        final int[] physicalStreamForCarUsages = new int[CarAudioManager.CAR_AUDIO_USAGE_MAX + 1];
        for (int i = 0; i < physicalStreamForCarUsages.length; i++) {
            physicalStreamForCarUsages[i] = USAGE_TYPE_INVALID;
        }
        int defaultCarUsage = USAGE_TYPE_INVALID;
        for (String streamPolicy : streamPolicies) {
            String[] numberVsStreams = streamPolicy.split(":");
            int physicalStream = Integer.parseInt(numberVsStreams[0]);
            String[] carUsages = numberVsStreams[1].split(",");
            int[] carUsagesInt = new int[carUsages.length];
            for (int i = 0; i < carUsages.length; i++) {
                int carUsageInt = getCarUsage(carUsages[i]);
                if (carUsageInt == CarAudioManager.CAR_AUDIO_USAGE_DEFAULT) {
                    defaultCarUsage = physicalStream;
                }
                carUsagesInt[i] = carUsageInt;
                physicalStreamForCarUsages[carUsageInt] = physicalStream;
                mCarUsageToPhysicalStream[carUsageInt] = physicalStream;
            }
            Arrays.sort(carUsagesInt);
            mPhysicalStreamToCarUsages[physicalStream] = carUsagesInt;
        }
        if (defaultCarUsage == USAGE_TYPE_INVALID) {
            Log.e(CarLog.TAG_AUDIO, "Audio routing policy did not include unknown");
            defaultCarUsage = 0;
        }
        for (int i = 0; i < physicalStreamForCarUsages.length; i++) {
            if (physicalStreamForCarUsages[i] == USAGE_TYPE_INVALID) {
                Log.w(CarLog.TAG_AUDIO, "Audio routing policy did not cover car usage " + i);
                physicalStreamForCarUsages[i] = defaultCarUsage;
            }
        }
    }

    public int getPhysicalStreamsCount() {
        return mPhysicalStreamToCarUsages.length;
    }

    public int[] getCarUsagesForPhysicalStream(int physicalStreamNumber) {
        return mPhysicalStreamToCarUsages[physicalStreamNumber];
    }

    public int getPhysicalStreamForCarUsage(@CarAudioManager.CarAudioUsage int carUsage) {
        return mCarUsageToPhysicalStream[carUsage];
    }

    public void dump(PrintWriter writer) {
        writer.println("*AudioRoutingPolicy*");
        writer.println("**Car Usages**");
        for (int i = 0; i < mPhysicalStreamToCarUsages.length; i++) {
            writer.print("physical stream " + i + ":");
            for (int carUsage : mPhysicalStreamToCarUsages[i]) {
                writer.print(Integer.toString(carUsage) + ",");
            }
            writer.println("");
        }
    }
}
