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
package android.support.car.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.AudioRecord;
import android.support.car.CarNotConnectedException;

/**
 * @hide
 */
public class CarAudioManagerEmbedded extends CarAudioManager {

    private static final int MAX_BUFFER_SIZE_BYTE = 512 * 1024;
    private static final int SAMPLING_RATE = 16000;
    private static final AudioFormat AUDIO_RECORD_FORMAT = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setSampleRate(SAMPLING_RATE)
            .build();

    private final android.car.media.CarAudioManager mManager;

    public CarAudioManagerEmbedded(Object manager) {
        mManager = (android.car.media.CarAudioManager) manager;
    }

    @Override
    public AudioAttributes getAudioAttributesForCarUsage(@CarAudioUsage int carUsage) {
        return mManager.getAudioAttributesForCarUsage(carUsage);
    }

    @Override
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            AudioAttributes requestAttributes,
            int durationHint,
            int flags) throws IllegalArgumentException {
        return mManager.requestAudioFocus(l, requestAttributes, durationHint, flags);
    }

    @Override
    public int requestAudioFocus(OnAudioFocusChangeListener l,
            AudioAttributes requestAttributes,
            int durationHint) throws IllegalArgumentException {
        return mManager.requestAudioFocus(l, requestAttributes, durationHint, 0 /*flags*/);
    }

    @Override
    public int abandonAudioFocus(OnAudioFocusChangeListener l, AudioAttributes aa) {
        return mManager.abandonAudioFocus(l, aa);
    }

    @Override
    public AudioFormat getAudioRecordAudioFormat() {
        return AUDIO_RECORD_FORMAT;
    }

    @Override
    public int getAudioRecordMinBufferSize() {
        return AudioRecord.getMinBufferSize(SAMPLING_RATE, AUDIO_RECORD_FORMAT.getChannelMask(),
                AUDIO_RECORD_FORMAT.getEncoding());
    }

    @Override
    public int getAudioRecordMaxBufferSize() {
        return Math.max(getAudioRecordMinBufferSize(), MAX_BUFFER_SIZE_BYTE);
    }

    @Override
    public CarAudioRecord createCarAudioRecord(int bufferSize) throws SecurityException {
        if (bufferSize < getAudioRecordMinBufferSize() ||
            bufferSize > getAudioRecordMaxBufferSize()) {
            throw new IllegalArgumentException("Bad bufferSize value");
        }
        return new CarAudioRecordEmbedded(AUDIO_RECORD_FORMAT, bufferSize);
    }

    @Override
    public boolean isMediaMuted() throws CarNotConnectedException {
        try {
            return mManager.isMediaMuted();
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public boolean setMediaMute(boolean mute) throws CarNotConnectedException {
        try {
            return mManager.setMediaMute(mute);
        } catch (android.car.CarNotConnectedException e) {
            throw new CarNotConnectedException(e);
        }
    }

    @Override
    public void onCarDisconnected() {
        //nothing to do
    }
}
