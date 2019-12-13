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

package com.android.car;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.car.Car;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.systeminterface.ActivityManagerInterface;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.StorageMonitoringInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemInterface.Builder;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.TimeInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.test.utils.TemporaryDirectory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;

/**
 * This class contains unit tests for the {@link ICarImpl}.
 * It tests that services started with {@link ICarImpl} are initialized properly.
 *
 * The following mocks are used:
 * 1. {@link ActivityManagerInterface} broadcasts intent for a user.
 * 2. {@link DisplayInterface} provides access to display operations.
 * 3. {@link IVehicle} provides access to vehicle properties.
 * 4. {@link StorageMonitoringInterface} provides access to storage monitoring operations.
 * 5. {@link SystemStateInterface} provides system statuses (booting, sleeping, ...).
 * 6. {@link TimeInterface} provides access to time operations.
 * 7. {@link TimeInterface} provides access to wake lock operations.
 */
@RunWith(MockitoJUnitRunner.class)
public class ICarImplTest {
    private static final String TAG = "ICarImplTest";

    @Mock private ActivityManagerInterface mMockActivityManagerInterface;
    @Mock private DisplayInterface mMockDisplayInterface;
    @Mock private IVehicle mMockVehicle;
    @Mock private StorageMonitoringInterface mMockStorageMonitoringInterface;
    @Mock private SystemStateInterface mMockSystemStateInterface;
    @Mock private TimeInterface mMockTimeInterface;
    @Mock private WakeLockInterface mMockWakeLockInterface;

    private Context mContext;
    private MockitoSession mSession;
    private SystemInterface mFakeSystemInterface;
    private UserManager mUserManager;

    private final MockIOInterface mMockIOInterface = new MockIOInterface();

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUp() throws Exception {
        mSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652.
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mContext = spy(InstrumentationRegistry.getInstrumentation().getTargetContext());

        mUserManager = spy(mContext.getSystemService(UserManager.class));
        doReturn(mUserManager).when(mContext).getSystemService(eq(UserManager.class));
        doReturn(mUserManager).when(mContext).getSystemService(eq(Context.USER_SERVICE));

        Resources resources = spy(mContext.getResources());
        doReturn("").when(resources).getString(
                eq(com.android.car.R.string.instrumentClusterRendererService));
        doReturn(false).when(resources).getBoolean(
                eq(com.android.car.R.bool.audioUseDynamicRouting));
        doReturn(new String[0]).when(resources).getStringArray(
                eq(com.android.car.R.array.config_earlyStartupServices));
        doReturn(resources).when(mContext).getResources();

        mFakeSystemInterface = Builder.newSystemInterface()
                .withSystemStateInterface(mMockSystemStateInterface)
                .withActivityManagerInterface(mMockActivityManagerInterface)
                .withDisplayInterface(mMockDisplayInterface)
                .withIOInterface(mMockIOInterface)
                .withStorageMonitoringInterface(mMockStorageMonitoringInterface)
                .withTimeInterface(mMockTimeInterface)
                .withWakeLockInterface(mMockWakeLockInterface).build();
        // ICarImpl will register new CarLocalServices services.
        // This prevents one test failure in tearDown from triggering assertion failure for single
        // CarLocalServices service.
        CarLocalServices.removeAllServices();
    }

    /**
     *  Clean up before running the next test.
     */
    @After
    public void tearDown() {
        if (mMockIOInterface != null) {
            mMockIOInterface.tearDown();
        }
        mSession.finishMocking();
        CarLocalServices.removeAllServices();
    }

    @Test
    public void testNoShardedPreferencesAccessedBeforeUserZeroUnlock() {
        doReturn(true).when(mContext).isCredentialProtectedStorage();
        doReturn(false).when(mUserManager).isUserUnlockingOrUnlocked(anyInt());
        doReturn(false).when(mUserManager).isUserUnlocked();
        doReturn(false).when(mUserManager).isUserUnlocked(anyInt());
        doReturn(false).when(mUserManager).isUserUnlocked(any(UserHandle.class));
        doReturn(false).when(mUserManager).isUserUnlockingOrUnlocked(any(UserHandle.class));

        doThrow(new NullPointerException()).when(mContext).getSharedPrefsFile(anyString());
        doThrow(new NullPointerException()).when(mContext).getSharedPreferencesPath(any());
        doThrow(new NullPointerException()).when(mContext).getSharedPreferences(
                anyString(), anyInt());
        doThrow(new NullPointerException()).when(mContext).getSharedPreferences(
                any(File.class), anyInt());
        doThrow(new NullPointerException()).when(mContext).getDataDir();

        ICarImpl carImpl = new ICarImpl(mContext, mMockVehicle, mFakeSystemInterface,
                /* errorNotifier= */ null, "MockedCar");
        carImpl.init();
        Car mCar = new Car(mContext, carImpl, /* handler= */ null);

        // Post tasks for Handler Threads to ensure all the tasks that will be queued inside init
        // will be done.
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!HandlerThread.class.isInstance(t)) {
                continue;
            }
            HandlerThread ht = (HandlerThread) t;
            CarServiceUtils.runOnLooperSync(ht.getLooper(), () -> {
                // Do nothing, just need to make sure looper finishes current task.
            });
        }

        mCar.disconnect();
        carImpl.release();
    }

    static final class MockIOInterface implements IOInterface {
        private TemporaryDirectory mFilesDir = null;

        @Override
        public File getSystemCarDir() {
            if (mFilesDir == null) {
                try {
                    mFilesDir = new TemporaryDirectory(TAG);
                } catch (IOException e) {
                    Log.e(TAG, "failed to create temporary directory", e);
                    fail("failed to create temporary directory. exception was: " + e);
                }
            }
            return mFilesDir.getDirectory();
        }

        public void tearDown() {
            if (mFilesDir != null) {
                try {
                    mFilesDir.close();
                } catch (Exception e) {
                    Log.w(TAG, "could not remove temporary directory", e);
                }
            }
        }
    }
}
