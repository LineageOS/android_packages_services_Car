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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;

import android.car.Car;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

abstract class CarApiTestBase {
    protected static final long DEFAULT_WAIT_TIMEOUT_MS = 1000;

    private Car mCar;

    protected final DefaultServiceConnectionListener mConnectionListener =
            new DefaultServiceConnectionListener();

    @Before
    public final void connectToCar() throws Exception {
        mCar = Car.createCar(getContext(), mConnectionListener);
        mCar.connect();
        mConnectionListener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
    }

    @After
    public final void disconnectFromCar() throws Exception {
        mCar.disconnect();
    }

    protected Car getCar() {
        return mCar;
    }

    protected final Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    protected static void assertMainThread() {
        assertThat(Looper.getMainLooper().isCurrentThread()).isTrue();
    }

    protected static final class DefaultServiceConnectionListener implements ServiceConnection {
        private final Semaphore mConnectionWait = new Semaphore(0);

        public void waitForConnection(long timeoutMs) throws InterruptedException {
            mConnectionWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            assertMainThread();
            mConnectionWait.release();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            assertMainThread();
        }
    }
}
