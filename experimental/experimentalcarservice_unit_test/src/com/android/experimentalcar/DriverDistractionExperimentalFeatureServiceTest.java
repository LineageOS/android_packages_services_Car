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

package com.android.experimentalcar;

import static com.google.common.truth.Truth.assertThat;

import android.car.experimental.DriverAwarenessEvent;
import android.car.experimental.DriverAwarenessSupplierConfig;
import android.car.experimental.DriverAwarenessSupplierService;
import android.car.experimental.IDriverAwarenessSupplier;
import android.car.experimental.IDriverAwarenessSupplierCallback;
import android.content.Context;
import android.os.RemoteException;
import android.util.Pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;

@RunWith(MockitoJUnitRunner.class)
public class DriverDistractionExperimentalFeatureServiceTest {

    private static final long INITIAL_TIME = 1000L;
    private static final long PREFERRED_SUPPLIER_STALENESS = 10L;

    private final IDriverAwarenessSupplier mFallbackSupplier =
            new IDriverAwarenessSupplier.Stub() {
                @Override
                public void onReady() throws RemoteException {
                }

                @Override
                public void setCallback(IDriverAwarenessSupplierCallback callback)
                        throws RemoteException {
                }
            };
    private final DriverAwarenessSupplierConfig mFallbackConfig = new DriverAwarenessSupplierConfig(
            DriverAwarenessSupplierService.NO_STALENESS);

    private final IDriverAwarenessSupplier mPreferredSupplier =
            new IDriverAwarenessSupplier.Stub() {
                @Override
                public void onReady() throws RemoteException {
                }

                @Override
                public void setCallback(IDriverAwarenessSupplierCallback callback)
                        throws RemoteException {
                }
            };
    private final DriverAwarenessSupplierConfig mPreferredSupplierConfig =
            new DriverAwarenessSupplierConfig(PREFERRED_SUPPLIER_STALENESS);

    @Mock
    private Context mContext;

    private DriverDistractionExperimentalFeatureService mService;
    private FakeTimeSource mTimeSource;
    private FakeTimer mTimer;

    @Before
    public void setUp() throws Exception {
        mTimeSource = new FakeTimeSource(INITIAL_TIME);
        mTimer = new FakeTimer();
        mService = new DriverDistractionExperimentalFeatureService(mContext, mTimeSource, mTimer);
    }

    @After
    public void tearDown() throws Exception {
        if (mService != null) {
            mService.release();
        }
    }

    @Test
    public void testHandleDriverAwarenessEvent_updatesCurrentValue_withLatestEvent()
            throws Exception {
        mService.setDriverAwarenessSuppliers(Arrays.asList(
                new Pair<>(mFallbackSupplier, mFallbackConfig)));

        float firstEmittedEvent = 0.7f;
        emitEvent(mFallbackSupplier, INITIAL_TIME + 1, firstEmittedEvent);

        assertThat(getCurrentAwarenessValue()).isEqualTo(firstEmittedEvent);
    }

    @Test
    public void testHandleDriverAwarenessEvent_hasPreferredEvent_ignoresFallbackEvent()
            throws Exception {
        mService.setDriverAwarenessSuppliers(Arrays.asList(
                new Pair<>(mFallbackSupplier, mFallbackConfig),
                new Pair<>(mPreferredSupplier, mPreferredSupplierConfig)));

        // emit an event from the preferred supplier before the fallback supplier
        float preferredValue = 0.6f;
        emitEvent(mPreferredSupplier, INITIAL_TIME + 1, preferredValue);
        float fallbackValue = 0.7f;
        emitEvent(mFallbackSupplier, INITIAL_TIME + 2, fallbackValue);

        // even though the fallback supplier has a more recent timestamp, it is not the current
        // since the event from the preferred supplier is still fresh
        assertThat(getCurrentAwarenessValue()).isEqualTo(preferredValue);
    }

    @Test
    public void testHandleDriverAwarenessEvent_ignoresOldEvents() throws Exception {
        mService.setDriverAwarenessSuppliers(Arrays.asList(
                new Pair<>(mFallbackSupplier, mFallbackConfig)));

        float firstEmittedEvent = 0.7f;
        emitEvent(mFallbackSupplier, INITIAL_TIME + 1, firstEmittedEvent);
        long oldTime = INITIAL_TIME - 100;
        emitEvent(mFallbackSupplier, oldTime, 0.6f);

        // the event with the old timestamp shouldn't overwrite the value with a more recent
        // timestamp
        assertThat(getCurrentAwarenessValue()).isEqualTo(firstEmittedEvent);
    }

    @Test
    public void testPreferredAwarenessEvent_becomesStale_fallsBackToFallbackEvent()
            throws Exception {
        mService.setDriverAwarenessSuppliers(Arrays.asList(
                new Pair<>(mFallbackSupplier, mFallbackConfig),
                new Pair<>(mPreferredSupplier, mPreferredSupplierConfig)));

        // emit an event from the preferred supplier before the fallback supplier
        float preferredValue = 0.6f;
        long preferredEventTime = INITIAL_TIME + 1;
        mTimeSource.setElapsedRealtime(preferredEventTime);
        emitEvent(mPreferredSupplier, preferredEventTime, preferredValue);
        float fallbackValue = 0.7f;
        long fallbackEventTime = INITIAL_TIME + 2;
        mTimeSource.setElapsedRealtime(fallbackEventTime);
        emitEvent(mFallbackSupplier, fallbackEventTime, fallbackValue);

        // the preferred supplier still has a fresh event
        assertThat(getCurrentAwarenessValue()).isEqualTo(preferredValue);

        // go into the future
        mTimeSource.setElapsedRealtime(preferredEventTime + PREFERRED_SUPPLIER_STALENESS + 1);
        mTimer.executePendingTask();

        // the preferred supplier's data has become stale
        assertThat(getCurrentAwarenessValue()).isEqualTo(fallbackValue);
    }

    private float getCurrentAwarenessValue() {
        return mService.getCurrentDriverAwareness().mAwarenessEvent.getAwarenessValue();
    }

    /**
     * Handle an event as if it were emitted from the specified supplier with the specified time and
     * value.
     */
    private void emitEvent(IDriverAwarenessSupplier supplier, long time, float value)
            throws RemoteException {
        long maxStaleness;
        if (supplier == mFallbackSupplier) {
            maxStaleness = DriverAwarenessSupplierService.NO_STALENESS;
        } else {
            maxStaleness = PREFERRED_SUPPLIER_STALENESS;
        }
        mService.handleDriverAwarenessEvent(
                new DriverDistractionExperimentalFeatureService.DriverAwarenessEventWrapper(
                        new DriverAwarenessEvent(time, value),
                        supplier,
                        maxStaleness));
    }
}
