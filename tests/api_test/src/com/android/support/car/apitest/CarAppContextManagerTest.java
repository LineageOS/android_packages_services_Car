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

import android.support.car.Car;
import android.support.car.CarAppContextManager;
import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CarAppContextManagerTest extends CarApiTestBase {
    private static final String TAG = CarAppContextManager.class.getSimpleName();
    private CarAppContextManager mManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mManager = (CarAppContextManager) getCar().getCarManager(Car.APP_CONTEXT_SERVICE);
        assertNotNull(mManager);
    }

    public void testUnregisteredAccess() throws Exception {
        try {
            mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
            fail();
        } catch (IllegalStateException e) {
            // expected
        }
    }

    public void testRegisterNull() throws Exception {
        try {
            mManager.registerContextListener(null, 0);
            fail();
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testRegisterUnregister() throws Exception {
        ContextChangeListerner listener = new ContextChangeListerner();
        ContextChangeListerner listener2 = new ContextChangeListerner();
        mManager.registerContextListener(listener, 0);
        mManager.registerContextListener(listener2, 0);
        mManager.unregisterContextListener();
        // this one is no-op
        mManager.unregisterContextListener();
    }

    public void testContextChange() throws Exception {
        DefaultServiceConnectionListener connectionListener =
                new DefaultServiceConnectionListener();
        Car car2 = Car.createCar(getContext(), connectionListener, null);
        car2.connect();
        connectionListener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
        CarAppContextManager manager2 = (CarAppContextManager)
                car2.getCarManager(Car.APP_CONTEXT_SERVICE);
        assertNotNull(manager2);

        assertEquals(0, mManager.getActiveAppContexts());
        ContextChangeListerner owner = new ContextChangeListerner();
        ContextChangeListerner owner2 = new ContextChangeListerner();
        mManager.registerContextListener(owner, CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        manager2.registerContextListener(owner2, CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);

        mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        int expectedContexts = CarAppContextManager.APP_CONTEXT_NAVIGATION;
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertTrue(mManager.isOwningContext(expectedContexts));
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertTrue(owner2.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                expectedContexts));
        // owner should not get notification for its own change
        assertFalse(owner.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));

        mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        expectedContexts = CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND;
        assertTrue(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertTrue(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertTrue(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertTrue(owner2.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                expectedContexts));
        // owner should not get notification for its own change
        assertFalse(owner.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));

        // this should be no-op
        mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertFalse(owner2.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));
        assertFalse(owner.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));

        manager2.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertTrue(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertTrue(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertTrue(owner.waitForOwnershipLossAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                CarAppContextManager.APP_CONTEXT_NAVIGATION));

        // no-op as it is not owning it
        mManager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertTrue(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertTrue(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());

        mManager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertTrue(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        expectedContexts = CarAppContextManager.APP_CONTEXT_NAVIGATION;
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertTrue(owner2.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(owner.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));

        manager2.resetActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(mManager.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_NAVIGATION));
        assertFalse(manager2.isOwningContext(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND));
        expectedContexts = 0;
        assertEquals(expectedContexts, mManager.getActiveAppContexts());
        assertEquals(expectedContexts, manager2.getActiveAppContexts());
        assertTrue(owner.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));
        mManager.unregisterContextListener();
        manager2.unregisterContextListener();
    }

    public void testFilter() throws Exception {
        DefaultServiceConnectionListener connectionListener =
                new DefaultServiceConnectionListener();
        Car car2 = Car.createCar(getContext(), connectionListener);
        car2.connect();
        connectionListener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
        CarAppContextManager manager2 = (CarAppContextManager)
                car2.getCarManager(Car.APP_CONTEXT_SERVICE);
        assertNotNull(manager2);

        assertEquals(0, mManager.getActiveAppContexts());
        ContextChangeListerner owner = new ContextChangeListerner();
        ContextChangeListerner listener = new ContextChangeListerner();
        mManager.registerContextListener(owner, CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        manager2.registerContextListener(listener, CarAppContextManager.APP_CONTEXT_NAVIGATION);
        mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertTrue(listener.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                CarAppContextManager.APP_CONTEXT_NAVIGATION));
        mManager.setActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        assertFalse(listener.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));
        mManager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        assertFalse(listener.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));
        mManager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        assertTrue(listener.waitForContextChangeAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0));
    }

    private class ContextChangeListerner implements CarAppContextManager.AppContextChangeListener {
        private int mLastChangeEvent;
        private final Semaphore mChangeWait = new Semaphore(0);
        private int mLastLossEvent;
        private final Semaphore mLossEventWait = new Semaphore(0);

        public boolean waitForContextChangeAndAssert(long timeoutMs, int expectedContexts)
                throws Exception {
            if (!mChangeWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedContexts, mLastChangeEvent);
            return true;
        }

        public boolean waitForOwnershipLossAndAssert(long timeoutMs, int expectedContexts)
                throws Exception {
            if (!mLossEventWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedContexts, mLastLossEvent);
            return true;
        }

        @Override
        public void onAppContextChange(int activeContexts) {
            Log.i(TAG, "onAppContextChange " + Integer.toHexString(activeContexts));
            assertMainThread();
            mLastChangeEvent = activeContexts;
            mChangeWait.release();
        }

        @Override
        public void onAppContextOwnershipLoss(int context) {
            Log.i(TAG, "onAppContextOwnershipLoss " + Integer.toHexString(context));
            assertMainThread();
            mLastLossEvent = context;
            mLossEventWait.release();
        }
    }
}
