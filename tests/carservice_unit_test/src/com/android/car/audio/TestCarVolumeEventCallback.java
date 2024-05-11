/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.audio;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.car.media.CarVolumeGroupEvent;
import android.car.media.ICarVolumeEventCallback;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
final class TestCarVolumeEventCallback extends ICarVolumeEventCallback.Stub {
    private final long mTimeOutMs;
    private CountDownLatch mStatusLatch = new CountDownLatch(1);
    private List<CarVolumeGroupEvent> mVolumeGroupEvents;

    TestCarVolumeEventCallback(long timeOutMs) {
        mTimeOutMs = timeOutMs;
    }

    @Override
    public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
        mVolumeGroupEvents = volumeGroupEvents;
        mStatusLatch.countDown();
    }

    @Override
    public void onMasterMuteChanged(int zoneId, int flags) {
        mStatusLatch.countDown();
    }

    boolean waitForCallback() throws Exception {
        return mStatusLatch.await(mTimeOutMs, TimeUnit.MILLISECONDS);
    }

    List<CarVolumeGroupEvent> getVolumeGroupEvents() {
        return mVolumeGroupEvents;
    }

    public void reset() {
        mVolumeGroupEvents = null;
        mStatusLatch = new CountDownLatch(1);
    }
}
