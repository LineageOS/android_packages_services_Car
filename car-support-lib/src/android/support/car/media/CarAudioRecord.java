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

import android.media.AudioRecord;
import android.support.car.CarNotConnectedException;

/**
 * CarAudioRecord allows apps to use microphone.
 */
public interface CarAudioRecord {
    /**
     * Get buffer size which was specified in
     * {@link CarAudioManager#createCarAudioRecord(int)}
     * @return buffer size in bytes.
     */
    int getBufferSize();

    /**
     * Start audio recording.
     */
    void startRecording() throws CarNotConnectedException;

    /**
     * Stop audio recording. Calling stop multiple times will be a safe operation.
     */
    void stop();

    /**
     * Release native resource allocated for this instance. {@link CarAudioRecord} can no longer
     * be used once release is called.
     */
    void release();

    /** See {@link AudioRecord#getRecordingState() }. */
    int getRecordingState();

    /** See {@link AudioRecord#getState() }. */
    int getState();

    /** See {@link AudioRecord#getAudioSessionId() }. */
    int getAudioSessionId();

    /**
     * Read recorded audio. Note that audio recording should be started with
     * {@link #startRecording()} before this.
     * @param audioData
     * @param offsetInBytes
     * @param sizeInBytes
     * @return Number of bytes read. will return {@link android.media.AudioRecord#ERROR} on error.
     * @throws IllegalStateException if audio recording was not started.
     */
    int read(byte[] audioData, int offsetInBytes, int sizeInBytes)
            throws IllegalStateException, CarNotConnectedException;
}
