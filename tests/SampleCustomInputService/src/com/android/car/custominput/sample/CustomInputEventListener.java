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

package com.android.car.custominput.sample;

import static android.car.input.CarInputManager.TargetDisplayType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityOptions;
import android.car.input.CarInputManager;
import android.car.input.CustomInputEvent;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Handles incoming {@link CustomInputEvent}. In this implementation, incoming events are expected
 * to have the display id and the function set.
 */
final class CustomInputEventListener {

    private static final String TAG = CustomInputEventListener.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SampleCustomInputService mService;
    private final Context mContext;

    /** List of defined actions for this reference service implementation */
    @IntDef({EventAction.LAUNCH_MAPS_ACTION,
            EventAction.ACCEPT_INCOMING_CALL_ACTION, EventAction.REJECT_INCOMING_CALL_ACTION,
            EventAction.INCREASE_SOUND_VOLUME_ACTION, EventAction.DECREASE_SOUND_VOLUME_ACTION,
            EventAction.BACK_HOME_ACTION})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventAction {

        /** Launches Map action. */
        int LAUNCH_MAPS_ACTION = CustomInputEvent.INPUT_CODE_F1;

        /** Accepts incoming call action. */
        int ACCEPT_INCOMING_CALL_ACTION = CustomInputEvent.INPUT_CODE_F2;

        /** Rejects incoming call action. */
        int REJECT_INCOMING_CALL_ACTION = CustomInputEvent.INPUT_CODE_F3;

        /** Increases volume action. */
        int INCREASE_SOUND_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F4;

        /** Increases volume action. */
        int DECREASE_SOUND_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F5;

        /** Simulates the HOME button (re-injects the HOME KeyEvent against Car Input API. */
        int BACK_HOME_ACTION = CustomInputEvent.INPUT_CODE_F6;
    }

    CustomInputEventListener(
            @NonNull Context context,
            @NonNull SampleCustomInputService service) {
        mContext = context;
        mService = service;
    }

    void handle(@TargetDisplayType int targetDisplayType, CustomInputEvent event) {
        if (!isValidTargetDisplayType(targetDisplayType)) {
            return;
        }
        int targetDisplayId = getDisplayIdForDisplayType(targetDisplayType);
        @EventAction int action = event.getInputCode();
        switch (action) {
            case EventAction.LAUNCH_MAPS_ACTION:
                launchMap(targetDisplayId);
                break;
            case EventAction.ACCEPT_INCOMING_CALL_ACTION:
                acceptIncomingCall(targetDisplayId);
                break;
            case EventAction.REJECT_INCOMING_CALL_ACTION:
                rejectIncomingCall(targetDisplayId);
                break;
            case EventAction.INCREASE_SOUND_VOLUME_ACTION:
                increaseVolume(targetDisplayId);
                break;
            case EventAction.DECREASE_SOUND_VOLUME_ACTION:
                decreaseVolume(targetDisplayId);
                break;
            case EventAction.BACK_HOME_ACTION:
                backHome(targetDisplayType);
                break;
            default:
                Log.e(TAG, "Ignoring event [" + action + "]");
        }
    }

    private int getDisplayIdForDisplayType(/* unused for now */
            @TargetDisplayType int targetDisplayType) {
        // TODO(159623196): convert the displayType to displayId using OccupantZoneManager api and
        //                  add tests. For now, we're just returning the display type.
        return 0;  // Hardcoded to return main display id for now.
    }

    private static boolean isValidTargetDisplayType(@TargetDisplayType int displayType) {
        if (displayType == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        Log.w(TAG,
                "This service implementation can only handle CustomInputEvent with "
                        + "targetDisplayType set to main display (main display type is {"
                        + CarInputManager.TARGET_DISPLAY_TYPE_MAIN + "}), current display type is {"
                        + displayType + "})");
        return false;
    }

    private void launchMap(int targetDisplayId) {
        if (DEBUG) {
            Log.d(TAG, "Launching Maps on display id {" + targetDisplayId + "}");
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(targetDisplayId);
        Intent mapsIntent = new Intent(Intent.ACTION_VIEW);
        mapsIntent.setClassName(mContext.getString(R.string.maps_app_package),
                mContext.getString(R.string.maps_activity_class));
        mapsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mService.startActivityAsUser(mapsIntent, options.toBundle(), UserHandle.CURRENT);
    }

    private void acceptIncomingCall(int targetDisplayId) {
        // TODO(b/159623196): When implementing this method, avoid using
        //     TelecomManager#acceptRingingCall deprecated method.
        if (DEBUG) {
            Log.d(TAG, "Accepting incoming call on display id {" + targetDisplayId + "}");
        }
    }

    private void rejectIncomingCall(int targetDisplayId) {
        // TODO(b/159623196): When implementing this method, avoid using
        //     TelecomManager#endCall deprecated method.
        if (DEBUG) {
            Log.d(TAG, "Rejecting incoming call on display id {" + targetDisplayId + "}");
        }
    }

    private void increaseVolume(int targetDisplayId) {
        // TODO(b/159623196): Provide implementation.
        if (DEBUG) {
            Log.d(TAG, "Increasing volume on display id {" + targetDisplayId + "}");
        }
    }

    private void decreaseVolume(int targetDisplayId) {
        // TODO(b/159623196): Provide implementation.
        if (DEBUG) {
            Log.d(TAG, "Decreasing volume on display id {" + targetDisplayId + "}");
        }
    }

    private void backHome(@TargetDisplayType int targetDisplayType) {
        if (DEBUG) {
            Log.d(TAG, "Injecting HOME KeyEvent on display type {" + targetDisplayType + "}");
        }

        KeyEvent e = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME);
        e.setDisplayId(Display.INVALID_DISPLAY);

        // Re-injecting KeyEvent.KEYCODE_HOME
        mService.injectKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME),
                targetDisplayType);

        mService.injectKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME),
                targetDisplayType);
    }
}
