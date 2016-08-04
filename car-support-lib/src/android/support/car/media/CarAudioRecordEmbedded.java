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
package android.support.car.media;

import android.media.AudioFormat;
import android.media.AudioRecord;

/**
 * CarAudioRecordEmbedded allows apps to use microphone.
 * @hide
 */
public class CarAudioRecordEmbedded implements CarAudioRecord {

    private final AudioFormat mFormat;
    private final int mBufferSize;
    private final AudioRecord mAudioRecord;


    CarAudioRecordEmbedded(AudioFormat format, int bufferSize) {
        mFormat = format;
        mBufferSize = bufferSize;
        mAudioRecord = new AudioRecord.Builder()
                .setAudioFormat(mFormat)
                .setBufferSizeInBytes(mBufferSize)
                .build();
    }

    @Override
    public int getBufferSize() {
        return mBufferSize;
    }

    @Override
    public void startRecording() {
        mAudioRecord.startRecording();
    }

    @Override
    public void stop() {
        mAudioRecord.stop();
    }

    @Override
    public void release() {
        mAudioRecord.release();
    }

    @Override
    public int getRecordingState() {
        return mAudioRecord.getRecordingState();
    }

    @Override
    public int getState() {
        return mAudioRecord.getState();
    }

    @Override
    public int getAudioSessionId() {
        return mAudioRecord.getAudioSessionId();
    }

    @Override
    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes)
            throws IllegalStateException {
        return mAudioRecord.read(audioData, offsetInBytes, sizeInBytes);
    }
}
