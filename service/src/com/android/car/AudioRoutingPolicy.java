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

import java.io.PrintWriter;
import java.util.Arrays;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.util.Log;

/**
 * Holds audio routing policy from config.xml. R.array.audioRoutingPolicy can contain
 * multiple policies and VEHICLE_PROPERTY_AUDIO_HW_VARIANT decide which one to use.
 */
public class AudioRoutingPolicy {
    /**
     * Type of logical stream defined in res/values/config.xml. This definition should be
     * in line with the file.
     */
    public static final int STREAM_TYPE_INVALID = -1;
    public static final int STREAM_TYPE_CALL = 0;
    public static final int STREAM_TYPE_MEDIA = 1;
    public static final int STREAM_TYPE_NAV_GUIDANCE = 2;
    public static final int STREAM_TYPE_VOICE_COMMAND = 3;
    public static final int STREAM_TYPE_ALARM = 4;
    public static final int STREAM_TYPE_NOTIFICATION = 5;
    public static final int STREAM_TYPE_UNKNOWN = 6;
    public static final int STREAM_TYPE_MAX = STREAM_TYPE_UNKNOWN;

    /** Physical stream to logical streams mapping */
    private final int[][] mLogicalStreams;
    /** Logical stream to physical stream mapping */
    private final int[] mPhisicalStreamForLogicalStream;

    public static AudioRoutingPolicy create(Context context, int policyNumber) {
        final Resources res = context.getResources();
        String[] policies = res.getStringArray(R.array.audioRoutingPolicy);
        return new AudioRoutingPolicy(policies[policyNumber]);
    }

    private static int getStreamType(String str) {
        switch (str) {
            case "call":
                return STREAM_TYPE_CALL;
            case "media":
                return STREAM_TYPE_MEDIA;
            case "nav_guidance":
                return STREAM_TYPE_NAV_GUIDANCE;
            case "voice_command":
                return STREAM_TYPE_VOICE_COMMAND;
            case "alarm":
                return STREAM_TYPE_ALARM;
            case "notification":
                return STREAM_TYPE_NOTIFICATION;
            case "unknown":
                return STREAM_TYPE_UNKNOWN;
        }
        throw new IllegalArgumentException("Wrong audioRoutingPolicy config, unknown stream type:" +
                str);
    }

    private AudioRoutingPolicy(String policy) {
        String[] streamPolicies = policy.split("#");
        final int nPhysicalStreams = streamPolicies.length;
        mLogicalStreams = new int[nPhysicalStreams][];
        mPhisicalStreamForLogicalStream = new int[STREAM_TYPE_MAX + 1];
        for (int i = 0; i < mPhisicalStreamForLogicalStream.length; i++) {
            mPhisicalStreamForLogicalStream[i] = STREAM_TYPE_INVALID;
        }
        int defaultStreamType = STREAM_TYPE_INVALID;
        for (String streamPolicy : streamPolicies) {
            String[] numberVsStreams = streamPolicy.split(":");
            int physicalStream = Integer.parseInt(numberVsStreams[0]);
            String[] logicalStreams = numberVsStreams[1].split(",");
            int[] logicalStreamsInt = new int[logicalStreams.length];
            for (int i = 0; i < logicalStreams.length; i++) {
                int logicalStreamNumber = getStreamType(logicalStreams[i]);
                if (logicalStreamNumber == STREAM_TYPE_UNKNOWN) {
                    defaultStreamType = physicalStream;
                }
                logicalStreamsInt[i] = logicalStreamNumber;
                mPhisicalStreamForLogicalStream[logicalStreamNumber] = physicalStream;
            }
            Arrays.sort(logicalStreamsInt);
            mLogicalStreams[physicalStream] = logicalStreamsInt;
        }
        if (defaultStreamType == STREAM_TYPE_INVALID) {
            Log.e(CarLog.TAG_AUDIO, "Audio routing policy did not include unknown");
            defaultStreamType = 0;
        }
        for (int i = 0; i < mPhisicalStreamForLogicalStream.length; i++) {
            if (mPhisicalStreamForLogicalStream[i] == STREAM_TYPE_INVALID) {
                Log.w(CarLog.TAG_AUDIO, "Audio routing policy did not cover logical stream " + i);
                mPhisicalStreamForLogicalStream[i] = defaultStreamType;
            }
        }
    }

    public int getPhysicalStreamsCount() {
        return mLogicalStreams.length;
    }

    public int[] getLogicalStreamsForPhysicalStream(int physicalStreamNumber) {
        return mLogicalStreams[physicalStreamNumber];
    }

    public int getPhysicalStreamForLogicalStream(int logicalStream) {
        return mPhisicalStreamForLogicalStream[logicalStream];
    }

    public int getLogicalStreamFromAudioAttributes(AudioAttributes attrib) {
        if (attrib == null) {
            return STREAM_TYPE_UNKNOWN;
        }
        int routingLogicalStream = STREAM_TYPE_UNKNOWN;
        switch (attrib.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
                routingLogicalStream = STREAM_TYPE_MEDIA;
                break;
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                routingLogicalStream = STREAM_TYPE_CALL;
                break;
            case AudioAttributes.USAGE_ALARM:
                routingLogicalStream = STREAM_TYPE_ALARM;
                break;
            case AudioAttributes.USAGE_NOTIFICATION:
                routingLogicalStream = STREAM_TYPE_NOTIFICATION;
                break;
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                routingLogicalStream = STREAM_TYPE_CALL;
                break;
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
            case AudioAttributes.USAGE_NOTIFICATION_EVENT:
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                routingLogicalStream = STREAM_TYPE_NOTIFICATION;
                break;
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                routingLogicalStream = STREAM_TYPE_NAV_GUIDANCE;
                break;
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                routingLogicalStream = STREAM_TYPE_NOTIFICATION;
                break;
            case AudioAttributes.USAGE_GAME:
                routingLogicalStream = STREAM_TYPE_MEDIA;
                break;
        }
        return routingLogicalStream;
    }

    public void dump(PrintWriter writer) {
        writer.println("*AudioRoutingPolicy*");
        writer.println("**Logical Streams**");
        for (int i = 0; i < mLogicalStreams.length; i++) {
            writer.print("physical stream " + i + ":");
            for (int logicalStream : mLogicalStreams[i]) {
                writer.print(Integer.toString(logicalStream) + ",");
            }
            writer.println("");
        }
    }
}
