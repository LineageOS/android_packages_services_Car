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

package com.android.support.car.apitest;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.Looper;
import android.support.car.Car;
import android.support.car.CarConnectionListener;
import android.support.car.ServiceConnectionListener;
import android.test.AndroidTestCase;
import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CarConnectionListenerTest extends AndroidTestCase {
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 1000;
    private static final String TAG = CarConnectionListenerTest.class.getSimpleName();

    private final Semaphore mConnectionWait = new Semaphore(0);

    private Car mCar;

    private final ServiceConnectionListener mConnectionListener = new ServiceConnectionListener() {

        @Override
        public void onServiceSuspended(int cause) {
            assertMainThread();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            assertMainThread();
        }

        @Override
        public void onServiceConnectionFailed(int cause) {
            assertMainThread();
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            assertMainThread();
            mConnectionWait.release();
        }
    };

    private void assertMainThread() {
        assertTrue(Looper.getMainLooper().isCurrentThread());
    }
    private void waitForConnection(long timeoutMs) throws InterruptedException {
        mConnectionWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCar = new Car(getContext(), mConnectionListener, null);
        mCar.connect();
        waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mCar.disconnect();
    }

    public void testRegisterUnregister() throws Exception {
        CarConnectionListerImpl listener = new CarConnectionListerImpl();
        mCar.registerCarConnectionListener(listener);
        assertTrue(listener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS));
        mCar.unregisterCarConnectionListener(listener);
    }

    public void testMultiple() throws Exception {
        CarConnectionListerImpl listener1 = new CarConnectionListerImpl();
        mCar.registerCarConnectionListener(listener1);
        assertTrue(listener1.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS));
        CarConnectionListerImpl listener2 = new CarConnectionListerImpl();
        mCar.registerCarConnectionListener(listener2);
        assertTrue(listener2.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS));
        assertFalse(listener1.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS));
        mCar.unregisterCarConnectionListener(listener2);
        mCar.unregisterCarConnectionListener(listener1);
    }

    private class CarConnectionListerImpl implements CarConnectionListener {
        int mConnectionType;
        boolean mIsConnected = false;
        private Semaphore mWaitSemaphore = new Semaphore(0);

        @Override
        public void onConnected(int connectionType) {
            Log.i(TAG, "onConnected " + connectionType);
            mConnectionType = connectionType;
            mIsConnected = true;
            mWaitSemaphore.release();
        }

        @Override
        public void onDisconnected() {
            Log.i(TAG, "onDisconnected");
            mIsConnected = false;
            mWaitSemaphore.release();
        }

        public boolean waitForConnection(long timeoutMs) throws Exception {
            if (!mWaitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertTrue(mIsConnected);
            assertEquals(Car.CONNECTION_TYPE_EMBEDDED, mConnectionType);
            return true;
        }

        public boolean waitForDisconnect(long timeoutMs) throws Exception {
            if (!mWaitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertFalse(mIsConnected);
            return true;
        }
    }
}
