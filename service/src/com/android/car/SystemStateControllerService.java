/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.util.Log;

import com.android.car.CarPowerManagementService.PowerEventProcessingHandler;
import com.android.car.CarPowerManagementService.PowerServiceEventListener;

import java.io.PrintWriter;

public class SystemStateControllerService implements CarServiceBase,
    PowerServiceEventListener, PowerEventProcessingHandler {

    private final CarPowerManagementService mCarPowerManagementService;
    private final MediaSilencer mMediaSilencer;

    public SystemStateControllerService(Context context,
            CarPowerManagementService carPowerManagementSercvice) {
        mCarPowerManagementService = carPowerManagementSercvice;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mMediaSilencer = new MediaSilencer(audioManager);
    }

    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        //TODO add state saving here for things to restore on power on.
        return 0;
    }

    @Override
    public void onPowerOn(boolean displayOn) {
        if (displayOn) {
            Log.i(CarLog.TAG_SYS, "MediaSilencer unmute");
            mMediaSilencer.unmuteMedia();
        } else {
            Log.i(CarLog.TAG_SYS, "MediaSilencer mute");
            mMediaSilencer.muteMedia();
        }
    }

    @Override
    public int getWakeupTime() {
        return 0;
    }

    @Override
    public void onShutdown() {
        // TODO
    }

    @Override
    public void onSleepEntry() {
        // TODO
    }

    @Override
    public void onSleepExit() {
        // TODO
    }

    @Override
    public void init() {
        mCarPowerManagementService.registerPowerEventListener(this);
        mCarPowerManagementService.registerPowerEventProcessingHandler(this);
    }

    @Override
    public void release() {
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("Media silence, ismuted:" + mMediaSilencer.isMuted());
    }

    private class MediaSilencer implements AudioManager.OnAudioFocusChangeListener {

        private final AudioManager mAudioManager;
        private final AudioAttributes mAttribMusic;
        private boolean mMuteMedia;

        private MediaSilencer(AudioManager audioManager) {
            mAudioManager = audioManager;
            mAttribMusic = CarAudioAttributesUtil.getAudioAttributesForCarUsage(
                    CarAudioManager.CAR_AUDIO_USAGE_MUSIC);
        }

        private synchronized void muteMedia() {
            mMuteMedia = true;
            //TODO replace this into real mute request
            mAudioManager.requestAudioFocus(this, mAttribMusic, AudioManager.AUDIOFOCUS_GAIN,
                    AudioManager.AUDIOFOCUS_FLAG_DELAY_OK);
        }

        private synchronized void unmuteMedia() {
            mMuteMedia = false;
            mAudioManager.abandonAudioFocus(this);
        }

        private synchronized boolean isMuted() {
            return mMuteMedia;
        }

        @Override
        public synchronized void onAudioFocusChange(int focusChange) {
            if (mMuteMedia) {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    Log.w(CarLog.TAG_SYS, "MediaSilencer got audio focus change:" + focusChange);
                    mAudioManager.requestAudioFocus(this, mAttribMusic,
                            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_FLAG_DELAY_OK);
                }
            } else {
                mAudioManager.abandonAudioFocus(this);
            }
        }
    }
}
