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
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.AttributeUsage;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityOptions;
import android.car.input.CarInputManager;
import android.car.input.CustomInputEvent;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Handles incoming {@link CustomInputEvent}. In this implementation, incoming events are expected
 * to have the display id and the function set.
 */
final class CustomInputEventListener {

    private static final String TAG = CustomInputEventListener.class.getSimpleName();

    private final SampleCustomInputService mService;
    private final Context mContext;
    private final CarAudioManager mCarAudioManager;

    /** List of defined actions for this reference service implementation */
    @IntDef({EventAction.LAUNCH_MAPS_ACTION,
            EventAction.ACCEPT_INCOMING_CALL_ACTION, EventAction.REJECT_INCOMING_CALL_ACTION,
            EventAction.INCREASE_MEDIA_VOLUME_ACTION, EventAction.DECREASE_MEDIA_VOLUME_ACTION,
            EventAction.INCREASE_ALARM_VOLUME_ACTION, EventAction.DECREASE_ALARM_VOLUME_ACTION,
            EventAction.BACK_HOME_ACTION})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventAction {

        /** Launches Map action. */
        int LAUNCH_MAPS_ACTION = CustomInputEvent.INPUT_CODE_F1;

        /** Accepts incoming call action. */
        int ACCEPT_INCOMING_CALL_ACTION = CustomInputEvent.INPUT_CODE_F2;

        /** Rejects incoming call action. */
        int REJECT_INCOMING_CALL_ACTION = CustomInputEvent.INPUT_CODE_F3;

        /** Increases media volume action. */
        int INCREASE_MEDIA_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F4;

        /** Increases media volume action. */
        int DECREASE_MEDIA_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F5;

        /** Increases alarm volume action. */
        int INCREASE_ALARM_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F6;

        /** Increases alarm volume action. */
        int DECREASE_ALARM_VOLUME_ACTION = CustomInputEvent.INPUT_CODE_F7;

        /** Simulates the HOME button (re-injects the HOME KeyEvent against Car Input API. */
        int BACK_HOME_ACTION = CustomInputEvent.INPUT_CODE_F8;
    }

    CustomInputEventListener(
            @NonNull Context context,
            @NonNull CarAudioManager carAudioManager,
            @NonNull SampleCustomInputService service) {
        mContext = context;
        mCarAudioManager = carAudioManager;
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
            case EventAction.INCREASE_MEDIA_VOLUME_ACTION:
                increaseVolume(targetDisplayId, AudioAttributes.USAGE_MEDIA);
                break;
            case EventAction.DECREASE_MEDIA_VOLUME_ACTION:
                decreaseVolume(targetDisplayId, AudioAttributes.USAGE_MEDIA);
                break;
            case EventAction.INCREASE_ALARM_VOLUME_ACTION:
                increaseVolume(targetDisplayId, AudioAttributes.USAGE_ALARM);
                break;
            case EventAction.DECREASE_ALARM_VOLUME_ACTION:
                decreaseVolume(targetDisplayId, AudioAttributes.USAGE_ALARM);
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
        // TODO(b/170233532): convert the displayType to displayId using OccupantZoneManager api and
        //                  add tests. For now, we're just returning the display type.
        return 0;  // Hardcoded to return main display id for now.
    }

    private int getOccupantZoneIdForDisplayId(/* unused for now */ int displayId) {
        // TODO(b/170975186): Use CarOccupantZoneManager to retrieve the associated zoneId with
        //                  the display id passed as parameter.
        return PRIMARY_AUDIO_ZONE;
    }

    private static boolean isValidTargetDisplayType(@TargetDisplayType int displayType) {
        if (displayType == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        Log.w(TAG,
                "This service implementation can only handle CustomInputEvent with "
                        + "targetDisplayType set to main display (main display type is {"
                        + CarInputManager.TARGET_DISPLAY_TYPE_MAIN
                        + "}), current display type is {"
                        + displayType + "})");
        return false;
    }

    private void launchMap(int targetDisplayId) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
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
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Accepting incoming call on display id {" + targetDisplayId + "}");
        }
    }

    private void rejectIncomingCall(int targetDisplayId) {
        // TODO(b/159623196): When implementing this method, avoid using
        //     TelecomManager#endCall deprecated method.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Rejecting incoming call on display id {" + targetDisplayId + "}");
        }
    }

    private void increaseVolume(int targetDisplayId, @AttributeUsage int usage) {
        int zoneId = getOccupantZoneIdForDisplayId(targetDisplayId);
        int volumeGroupId = mCarAudioManager.getVolumeGroupIdForUsage(zoneId, usage);
        int maxVolume = mCarAudioManager.getGroupMaxVolume(zoneId, volumeGroupId);
        int volume = mCarAudioManager.getGroupVolume(zoneId, volumeGroupId);
        Preconditions.checkArgument(maxVolume >= volume);
        String usageName = AudioAttributes.usageToString(usage);
        if (volume == maxVolume) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Current " + usageName + " volume is already equal to max volume ("
                        + maxVolume + ")");
            }
            return;
        }
        volume++;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Increasing " + usageName + " volume to: " + volume + " (max volume is "
                    + maxVolume + ")");
        }
        mCarAudioManager.setGroupVolume(volumeGroupId, volume, AudioManager.FLAG_SHOW_UI);
    }

    private void decreaseVolume(int targetDisplayId, @AttributeUsage int usage) {
        int zoneId = getOccupantZoneIdForDisplayId(targetDisplayId);
        int volumeGroupId = mCarAudioManager.getVolumeGroupIdForUsage(zoneId, usage);
        int minVolume = mCarAudioManager.getGroupMinVolume(zoneId, volumeGroupId);
        int volume = mCarAudioManager.getGroupVolume(zoneId, volumeGroupId);
        Preconditions.checkArgument(minVolume <= volume);
        String usageName = AudioAttributes.usageToString(usage);
        if (volume == minVolume) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Current " + usageName + " volume is already equal to min volume ("
                        + minVolume + ")");
            }
            return;
        }
        volume--;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Decreasing " + usageName + " volume to: " + volume + " (min volume is "
                    + minVolume + ")");
        }
        mCarAudioManager.setGroupVolume(volumeGroupId, volume, AudioManager.FLAG_SHOW_UI);
    }

    private void backHome(@TargetDisplayType int targetDisplayType) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Injecting HOME KeyEvent on display type {" + targetDisplayType + "}");
        }

        // Re-injecting KeyEvent.KEYCODE_HOME. Setting the event's display to INVALID_DISPLAY since
        // CarInputService will be properly assigning the correct display id from the display type
        // passed as argument.
        KeyEvent homeKeyDown = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME);
        homeKeyDown.setDisplayId(Display.INVALID_DISPLAY);
        mService.injectKeyEvent(homeKeyDown, targetDisplayType);

        KeyEvent homeKeyUp = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME);
        homeKeyUp.setDisplayId(Display.INVALID_DISPLAY);
        mService.injectKeyEvent(homeKeyUp, targetDisplayType);
    }
}
