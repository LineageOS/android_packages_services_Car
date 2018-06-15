/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import android.content.Context;
import android.util.Log;

import com.android.car.R;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Default garage mode policy.
 *
 * The first wake up time is set to be 1am the next day. And it keeps waking up every day for a
 * week. After that, wake up every 7 days for a month, and wake up every 30 days thereafter.
 */
public class GarageModePolicy {
    private static final String TAG = "GarageModePolicy";
    private static final Map<Character, Integer> TIME_UNITS_LOOKUP;
    static {
        TIME_UNITS_LOOKUP = new HashMap<>();
        TIME_UNITS_LOOKUP.put('m', 60);
        TIME_UNITS_LOOKUP.put('h', 3600);
        TIME_UNITS_LOOKUP.put('d', 86400);
    }

    private LinkedList<WakeupInterval> mWakeupIntervals;

    public GarageModePolicy(String[] policy) {
        mWakeupIntervals = parsePolicy(policy);
    }

    /**
     * Initializes GarageModePolicy from config_garageModeCadence resource array.
     * @param context to access resources
     * @return GarageModePolicy instance, created from values in resources
     */
    public static GarageModePolicy initFromResources(Context context) {
        return new GarageModePolicy(
                context.getResources().getStringArray(R.array.config_garageModeCadence));
    }

    /**
     * Returns the interval in milliseconds, which defines next wake up time.
     * @param index amount of times system woken up
     * @return the interval in milliseconds
     */
    public int getNextWakeUpInterval(int index) {
        if (mWakeupIntervals.size() == 0) {
            Log.e(TAG, "No wake up policy configuration was loaded.");
            return 0;
        }

        for (WakeupInterval wakeupTime : mWakeupIntervals) {
            if (index < wakeupTime.getNumAttempts()) {
                return wakeupTime.getWakeupInterval();
            }
            index -= wakeupTime.getNumAttempts();
        }
        Log.w(TAG, "No more garage mode wake ups scheduled; been sleeping too long.");
        return 0;
    }

    /**
     * Get list of {@link com.android.car.garagemode.WakeupInterval}s in this policy
     * @return list as List\<WakeupInterval\>
     */
    public List<WakeupInterval> getWakeupIntervals() {
        return mWakeupIntervals;
    }

    private LinkedList<WakeupInterval> parsePolicy(String[] policy) {
        LinkedList<WakeupInterval> intervals = new LinkedList<>();
        if (policy == null || policy.length == 0) {
            Log.e(TAG, "Trying to parse empty policies!");
            return intervals;
        }

        for (String rule : policy) {
            WakeupInterval interval = parseRule(rule);
            if (interval == null) {
                Log.e(TAG, "Invalid Policy! This rule has bad format: " + rule);
                return new LinkedList<>();
            }
            intervals.add(interval);
        }
        return intervals;
    }

    private WakeupInterval parseRule(String rule) {
        String[] str = rule.split(",");

        if (str.length != 2) {
            Log.e(TAG, "Policy has bad format: " + rule);
            return null;
        }

        String intervalStr = str[0];
        String timesStr = str[1];

        if (intervalStr.isEmpty() || timesStr.isEmpty()) {
            Log.e(TAG, "One of the values is empty. Please check format: " + rule);
            return null;
        }

        char unit = intervalStr.charAt(intervalStr.length() - 1);

        // Removing last letter extension from string
        intervalStr = intervalStr.substring(0, intervalStr.length() - 1);

        int interval, times;
        try {
            interval = Integer.parseInt(intervalStr);
            times = Integer.parseInt(timesStr);
        } catch (NumberFormatException ex)  {
            Log.d(TAG, "Invalid input Rule for interval " + rule);
            return null;
        }

        if (!TIME_UNITS_LOOKUP.containsKey(unit)) {
            Log.e(TAG, "Time units map does not contain extension " + unit);
            return null;
        }

        if (interval <= 0) {
            Log.e(TAG, "Wake up policy time must be > 0!" + interval);
            return null;
        }

        if (times <= 0) {
            Log.e(TAG, "Wake up attempts in policy must be > 0!" + times);
            return null;
        }

        interval *= TIME_UNITS_LOOKUP.get(unit);

        return new WakeupInterval(interval, times);
    }
}
