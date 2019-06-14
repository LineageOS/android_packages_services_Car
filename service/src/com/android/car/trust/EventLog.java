/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.trust;

import android.util.Log;

/**
 * Helper class for logging trusted device related events, e.g. unlock process.
 *
 * Events are logged with timestamp in fixed format for parsing and further analyzing.
 */
final class EventLog {
    private static final String UNLOCK_TAG = "CarTrustAgentUnlockEvent";

    static final String START_UNLOCK_ADVERTISING = "START_UNLOCK_ADVERTISING";
    static final String STOP_UNLOCK_ADVERTISING = "STOP_UNLOCK_ADVERTISING";
    static final String UNLOCK_SERVICE_INIT = "UNLOCK_SERVICE_INIT";
    static final String REMOTE_DEVICE_CONNECTED = "REMOTE_DEVICE_CONNECTED";
    static final String RECEIVED_DEVICE_ID = "RECEIVED_DEVICE_ID";
    static final String CLIENT_AUTHENTICATED = "CLIENT_AUTHENTICATED";
    static final String UNLOCK_TOKEN_RECEIVED = "UNLOCK_TOKEN_RECEIVED";
    static final String UNLOCK_HANDLE_RECEIVED = "UNLOCK_HANDLE_RECEIVED";
    static final String WAITING_FOR_CLIENT_AUTH = "WAITING_FOR_CLIENT_AUTH";
    static final String USER_UNLOCKED = "USER_UNLOCKED";
    static final String ENCRYPTION_STATE = "ENCRYPTION_STATE";
    static final String BLUETOOTH_STATE_CHANGED = "BLUETOOTH_STATE_CHANGED";

    private EventLog() {
        // Do not instantiate.
    }

    /**
     * Logs timestamp and event.
     * Format is "timestamp: <system time in milli-seconds> - <eventType>
     */
    static void logUnlockEvent(String eventType) {
        if (Log.isLoggable(UNLOCK_TAG, Log.INFO)) {
            Log.i(UNLOCK_TAG,
                    String.format("timestamp: %d - %s", System.currentTimeMillis(), eventType));
        }
    }

    /**
     * Logs timestamp, event, and value.
     * Format is "timestamp: <system time in milli-seconds> - <eventType>: <value>
     */
    static void logUnlockEvent(String eventType, int value) {
        if (Log.isLoggable(UNLOCK_TAG, Log.INFO)) {
            Log.i(UNLOCK_TAG, String.format("timestamp: %d - %s: %d",
                    System.currentTimeMillis(), eventType, value));
        }
    }
}
