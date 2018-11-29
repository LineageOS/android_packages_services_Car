/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.car.settings.CarSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.car.CarPowerManagementService;
import com.android.car.DeviceIdleControllerWrapper;
import com.android.car.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class GarageModeServiceTest {
    private static final int WAIT_FOR_COMPLETION_TIME_MS = 3000;

    @Test
    @UiThreadTest
    public void testMaintenanceActive() throws Exception {
        MockCarPowerManagementService powerManagementService = new MockCarPowerManagementService();
        MockDeviceIdleController controller = new MockDeviceIdleController(true);
        GarageModeServiceForTest garageMode = new GarageModeServiceForTest(getContext(),
                powerManagementService,
                controller);
        garageMode.init();
        final int index1 = garageMode.getGarageModeIndex();
        assertEquals(garageMode.getMaintenanceWindow(),
                powerManagementService.doNotifyPrepareShutdown(false));
        assertEquals(true, garageMode.isInGarageMode());
        assertEquals(true, garageMode.isMaintenanceActive());

        controller.setMaintenanceActivity(false);
        assertEquals(false, garageMode.isInGarageMode());
        assertEquals(false, garageMode.isMaintenanceActive());
        final int index2 = garageMode.getGarageModeIndex();

        assertEquals(1, index2 - index1);
    }

    @Test
    @UiThreadTest
    public void testMaintenanceInactive() throws Exception {
        MockCarPowerManagementService powerManagementService = new MockCarPowerManagementService();
        MockDeviceIdleController controller = new MockDeviceIdleController(false);
        GarageModeServiceForTest garageMode = new GarageModeServiceForTest(getContext(),
                powerManagementService,
                controller);
        garageMode.init();
        assertEquals(garageMode.getMaintenanceWindow(),
                powerManagementService.doNotifyPrepareShutdown(false));
        assertEquals(true, garageMode.isInGarageMode());
        assertEquals(false, garageMode.isMaintenanceActive());
    }

    @Test
    @UiThreadTest
    public void testDisplayOn() throws Exception {
        MockCarPowerManagementService powerManagementService = new MockCarPowerManagementService();
        MockDeviceIdleController controller = new MockDeviceIdleController(true);
        GarageModeServiceForTest garageMode = new GarageModeServiceForTest(getContext(),
                powerManagementService,
                controller);
        garageMode.init();

        powerManagementService.doNotifyPrepareShutdown(false);
        assertTrue(garageMode.getGarageModeIndex() > 0);
        powerManagementService.doNotifyPowerOn(true);
        assertEquals(0, garageMode.getGarageModeIndex());
    }

    @Test
    @UiThreadTest
    public void testPolicyIndexing() throws Exception {
        // Background processing of asynchronous messages.
        HandlerThread thread = new HandlerThread("testPolicy");
        thread.start();

        // Test that the index is saved in the prefs and that this index is used to determine the
        // next wakeup time.
        MockCarPowerManagementService powerManagementService = new MockCarPowerManagementService();
        MockDeviceIdleController controller = new MockDeviceIdleController(true);
        GarageModeServiceForTest garageMode = new GarageModeServiceForTest(getContext(),
                powerManagementService,
                controller,
                thread.getLooper());
        GarageModePolicy policy = new GarageModePolicy(new String[] {
                "15m,1",
                "6h,8",
                "1d,5",
        });
        SharedPreferences prefs =
                getContext().getSharedPreferences("testPolicy", Context.MODE_PRIVATE);
        prefs.edit().putInt("garage_mode_index", 0).apply();
        garageMode.init(policy, prefs);

        assertEquals(15 * 60, garageMode.getWakeupTime());
        garageMode.onPrepareShutdown(false);
        garageMode.onShutdown();
        assertEquals(6 * 60 * 60, garageMode.getWakeupTime());
        Thread.sleep(WAIT_FOR_COMPLETION_TIME_MS);
        assertEquals(1, prefs.getInt("garage_mode_index", 0));

        garageMode = new GarageModeServiceForTest(getContext(),
                powerManagementService,
                controller,
                thread.getLooper());
        // Jump ahead 8 restarts.
        prefs = getContext().getSharedPreferences("testPolicy", Context.MODE_PRIVATE);
        prefs.edit().putInt("garage_mode_index", 8).apply();
        garageMode.init(policy, prefs);

        assertEquals(6 * 60 * 60, garageMode.getWakeupTime());
        garageMode.onPrepareShutdown(false);
        garageMode.onShutdown();
        assertEquals(24 * 60 * 60, garageMode.getWakeupTime());
        Thread.sleep(WAIT_FOR_COMPLETION_TIME_MS);
        assertEquals(9, prefs.getInt("garage_mode_index", 0));
    }

    @Test
    public void testPolicyParserValid() throws Exception {
        WakeupInterval[] expected = new WakeupInterval[] {
            new WakeupInterval(15 * 60, 1),
            new WakeupInterval(6 * 60 * 60, 8),
            new WakeupInterval(24 * 60 * 60, 5),
        };
        List<WakeupInterval> received = new GarageModePolicy(new String[] {
                "15m,1",
                "6h,8",
                "1d,5",
        }).getWakeupIntervals();

        assertEquals(expected.length, received.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].getWakeupInterval(), received.get(i).getWakeupInterval());
            assertEquals(expected[i].getNumAttempts(), received.get(i).getNumAttempts());
        }
    }

    @Test
    public void testPolicyParser() {
        GarageModePolicy policy;

        policy = new GarageModePolicy(null);
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {""});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"15,1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"15y,1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"15m"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"15m,Q"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"15m,-1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {",1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"m,1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"Qm,1"});
        assertEquals(0, policy.getWakeupIntervals().size());

        policy = new GarageModePolicy(new String[] {"-10m,1"});
        assertEquals(0, policy.getWakeupIntervals().size());

    }

    @Test
    public void testPolicyInResource() throws Exception {
        // Test that the policy in the resource file parses fine.
        GarageModePolicy policy = new GarageModePolicy(getContext().getResources().getStringArray(
                R.array.config_garageModeCadence));
        assertTrue(policy.getWakeupIntervals().size() > 0);
    }

    private static class MockCarPowerManagementService extends CarPowerManagementService {
        public long doNotifyPrepareShutdown(boolean shuttingdown) {
            return notifyPrepareShutdown(shuttingdown);
        }

        public void doNotifyPowerOn(boolean displayOn) {
            notifyPowerOn(displayOn);
        }
    }

    private static class GarageModeServiceForTest extends GarageModeService {
        GarageModeServiceForTest(Context context,
                CarPowerManagementService powerManagementService,
                DeviceIdleControllerWrapper controllerWrapper,
                Looper looper) {
            super(context, powerManagementService, controllerWrapper, looper);
        }

        GarageModeServiceForTest(Context context,
                CarPowerManagementService powerManagementService,
                DeviceIdleControllerWrapper controllerWrapper) {
            super(context, powerManagementService, controllerWrapper, Looper.myLooper());
        }

        public long getMaintenanceWindow() {
            return CarSettings.DEFAULT_GARAGE_MODE_MAINTENANCE_WINDOW;
        }

        public boolean isInGarageMode() {
            synchronized (this) {
                return mInGarageMode;
            }
        }

        public boolean isMaintenanceActive() {
            synchronized (this) {
                return mMaintenanceActive;
            }
        }

        public int getGarageModeIndex() {
            synchronized (this) {
                return mGarageModeIndex;
            }
        }
    }

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    private static class MockDeviceIdleController extends DeviceIdleControllerWrapper {

        private final boolean mInitialActive;

        MockDeviceIdleController(boolean active) {
            super();
            mInitialActive = active;
        }

        @Override
        protected boolean startLocked() {
            return mInitialActive;
        }

        @Override
        public void stopTracking() {
            // nothing to clean up
        }

        @Override
        protected void reportActiveLocked(final boolean active) {
            // directly calling the callback instead of posting to handler, to make testing easier.
            if (mListener.get() != null) {
                mListener.get().onMaintenanceActivityChanged(active);
            }
        }

        public void setMaintenanceActivity(boolean active) {
            super.setMaintenanceActivity(active);
        }
    }
}
