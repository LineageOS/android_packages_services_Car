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

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
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

    /** Physical stream to {@link AudioAttributes} usages mapping */
    private final int[][] mPhysicalStreamToUsages;

    /** {@link AudioAttributes} usage to physical stream mapping */
    private final int[] mUsageToPhysicalStream;

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

    /**
     * TODO: b/72046976 abandon this method.
     * Since {@link AudioAttributes} usage to car audio context should be a static mapping
     * while mapping from car audio context to physical bus address should be part of
     * car audio control HAL.
     */
    private static @AudioAttributes.AttributeUsage int getUsage(String str) {
        switch (str) {
            default:
                return AudioAttributes.USAGE_UNKNOWN;
        }
    }

    private AudioRoutingPolicy(String policy) {
        mUsageToPhysicalStream = new int[AudioAttributes.SDK_USAGES.length];
        String[] streamPolicies = policy.split("#");
        mPhysicalStreamToUsages = new int[streamPolicies.length][];
        final int[] physicalStreamForUsages = new int[AudioAttributes.SDK_USAGES.length];
        for (int i = 0; i < physicalStreamForUsages.length; i++) {
            physicalStreamForUsages[i] = USAGE_TYPE_INVALID;
        }
        int defaultUsage = USAGE_TYPE_INVALID;
        for (String streamPolicy : streamPolicies) {
            String[] numberVsStreams = streamPolicy.split(":");
            int physicalStream = Integer.parseInt(numberVsStreams[0]);
            String[] usage = numberVsStreams[1].split(",");
            int[] usagesInt = new int[usage.length];
            for (int i = 0; i < usage.length; i++) {
                int usageInt = getUsage(usage[i]);
                if (usageInt == AudioAttributes.USAGE_UNKNOWN) {
                    defaultUsage = physicalStream;
                }
                usagesInt[i] = usageInt;
                physicalStreamForUsages[usageInt] = physicalStream;
                mUsageToPhysicalStream[usageInt] = physicalStream;
            }
            Arrays.sort(usagesInt);
            mPhysicalStreamToUsages[physicalStream] = usagesInt;
        }
        if (defaultUsage == USAGE_TYPE_INVALID) {
            Log.e(CarLog.TAG_AUDIO, "Audio routing policy did not include unknown");
            defaultUsage = 0;
        }
        for (int i = 0; i < physicalStreamForUsages.length; i++) {
            if (physicalStreamForUsages[i] == USAGE_TYPE_INVALID) {
                Log.w(CarLog.TAG_AUDIO, "Audio routing policy did not cover usage " + i);
                physicalStreamForUsages[i] = defaultUsage;
            }
        }
    }

    public int getPhysicalStreamsCount() {
        return mPhysicalStreamToUsages.length;
    }

    public int[] getUsagesForPhysicalStream(int physicalStreamNumber) {
        return mPhysicalStreamToUsages[physicalStreamNumber];
    }

    public int getPhysicalStreamForUsage(@AudioAttributes.AttributeUsage int usage) {
        return mUsageToPhysicalStream[usage];
    }

    public void dump(PrintWriter writer) {
        writer.println("*AudioRoutingPolicy*");
        writer.println("**AudioAttributes Usages**");
        for (int i = 0; i < mPhysicalStreamToUsages.length; i++) {
            writer.print("physical stream " + i + ":");
            for (int usage : mPhysicalStreamToUsages[i]) {
                writer.print(Integer.toString(usage) + ",");
            }
            writer.println("");
        }
    }
}
