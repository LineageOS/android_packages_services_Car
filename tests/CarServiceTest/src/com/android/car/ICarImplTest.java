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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.automotive.powerpolicy.internal.ICarPowerPolicyDelegate;
import android.car.Car;
import android.car.ICarResultReceiver;
import android.car.feature.Flags;
import android.content.Context;
import android.content.res.Resources;
import android.frameworks.automotive.powerpolicy.internal.ICarPowerPolicySystemNotification;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.audio.CarAudioService;
import com.android.car.garagemode.GarageModeService;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.PowerHalService;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.StaticBinderInterface;
import com.android.car.os.CarPerformanceService;
import com.android.car.provider.Settings;
import com.android.car.remoteaccess.CarRemoteAccessService;
import com.android.car.systeminterface.ActivityManagerInterface;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.StorageMonitoringInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.TimeInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.telemetry.CarTelemetryService;
import com.android.car.test.utils.TemporaryDirectory;
import com.android.car.user.CarUserService;
import com.android.car.watchdog.CarWatchdogService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;

/**
 * This class contains unit tests for the {@link ICarImpl}.
 *
 * <p>It tests that services started with {@link ICarImpl} are initialized properly.
 * <p>The following mocks are used:
 * <ol>
 * <li>{@link ActivityManagerInterface} broadcasts intent for a user.</li>
 * <li>{@link DisplayInterface} provides access to display operations.</li>
 * <li>{@link VehicleStub} provides access to vehicle properties.</li>
 * <li>{@link StorageMonitoringInterface} provides access to storage monitoring operations.</li>
 * <li>{@link SystemStateInterface} provides system statuses (booting, sleeping, ...).</li>
 * <li>{@link TimeInterface} provides access to time operations.</li>
 * <li>{@link WakeLockInterface} provides access to wake lock operations.</li>
 * <li>{@link CarWatchdogService}</li>
 * <li>{@link CarPerformanceService}</li>
 * <li>{@link GarageModeService}</li>
 * <li>{@link CarTelemetryService}</li>
 * <li>{@link CarRemoteAccessService}</li>
 * <li>{@link CarAudioService}</li>
 * <li>{@link CarUserService}</li>
 * <li>{@link ICarPowerPolicySystemNotification.Stub} car power policy daemon before
 * refactoring</li>
 * <li>{@link ICarPowerPolicyDelegate.Stub} car power policy daemon after refactoring.</li>
 * <li>{@link ICarServiceHelper}</li>
 * </ol>
 */
@RunWith(MockitoJUnitRunner.class)
public final class ICarImplTest {
    private static final String TAG = ICarImplTest.class.getSimpleName();

    @Mock private ActivityManagerInterface mMockActivityManagerInterface;
    @Mock private DisplayInterface mMockDisplayInterface;
    @Mock private VehicleStub mMockVehicle;
    @Mock private StorageMonitoringInterface mMockStorageMonitoringInterface;
    @Mock private SystemStateInterface mMockSystemStateInterface;
    @Mock private TimeInterface mMockTimeInterface;
    @Mock private WakeLockInterface mMockWakeLockInterface;
    @Mock private CarWatchdogService mMockCarWatchdogService;
    @Mock private CarPerformanceService mMockCarPerformanceService;
    @Mock private GarageModeService mMockGarageModeService;
    @Mock private CarTelemetryService mMockCarTelemetryService;
    @Mock private CarRemoteAccessService mMockCarRemoteAccessService;
    @Mock private CarAudioService mMockCarAudioService;
    @Mock private CarUserService mMockCarUserService;
    @Mock private ICarPowerPolicySystemNotification.Stub mMockCarPowerPolicyDaemon;
    @Mock private ICarPowerPolicyDelegate.Stub mMockRefactoredCarPowerPolicyDaemon;
    @Mock private ICarServiceHelper mICarServiceHelper;

    private Context mContext;
    private SystemInterface mFakeSystemInterface;
    private UserManager mUserManager;

    private final MockIOInterface mMockIOInterface = new MockIOInterface();
    private final StaticBinderInterface mFakeStaticBinderInterface = new StaticBinderInterface() {
        @Override
        public int getCallingUid() {
            return Process.SYSTEM_UID;
        }

        @Override
        public int getCallingPid() {
            return 0;
        }
    };

    static final class CarServiceConnectedCallback extends ICarResultReceiver.Stub {
        @Override
        public void send(int resultCode, Bundle resultData) {
            Log.i(TAG, "CarServiceConnectedCallback.send(int resultCode, Bundle resultData)");
        }
    }

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUp() throws Exception {
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

        mFakeSystemInterface = SystemInterface.Builder.newSystemInterface()
                .withSystemStateInterface(mMockSystemStateInterface)
                .withActivityManagerInterface(mMockActivityManagerInterface)
                .withDisplayInterface(mMockDisplayInterface)
                .withIOInterface(mMockIOInterface)
                .withStorageMonitoringInterface(mMockStorageMonitoringInterface)
                .withTimeInterface(mMockTimeInterface)
                .withSettings(new Settings.DefaultImpl())
                .withWakeLockInterface(mMockWakeLockInterface).build();
        // ICarImpl will register new CarLocalServices services.
        // This prevents one test failure in tearDown from triggering assertion failure for single
        // CarLocalServices service.
        CarLocalServices.removeAllServices();

        when(mMockVehicle.getHalPropValueBuilder()).thenReturn(
                new HalPropValueBuilder(/* isAidl= */ true));
    }

    /**
     *  Clean up before running the next test.
     */
    @After
    public void tearDown() {
        try {
            if (mMockIOInterface != null) {
                mMockIOInterface.tearDown();
            }
        } finally {
            CarLocalServices.removeAllServices();
        }
    }

    private IInterface getMockPowerPolicyDaemon() {
        if (Flags.carPowerPolicyRefactoring()) {
            return mMockRefactoredCarPowerPolicyDaemon;
        } else {
            return mMockCarPowerPolicyDaemon;
        }
    }

    @Test
    public void testNoShardedPreferencesAccessedBeforeUserZeroUnlock() throws Exception {
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

        IInterface powerPolicyDaemon = getMockPowerPolicyDaemon();
        // We use real CarUserService in this test.
        ICarImpl carImpl = new ICarImpl.Builder()
                .setServiceContext(mContext)
                .setVehicle(mMockVehicle)
                .setVehicleInterfaceName("MockedCar")
                .setSystemInterface(mFakeSystemInterface)
                .setCarWatchdogService(mMockCarWatchdogService)
                .setCarPerformanceService(mMockCarPerformanceService)
                .setCarTelemetryService(mMockCarTelemetryService)
                .setCarAudioService(mMockCarAudioService)
                .setCarRemoteAccessServiceConstructor((
                        Context context, SystemInterface systemInterface,
                        PowerHalService powerHalService
                    ) -> mMockCarRemoteAccessService)
                .setGarageModeService(mMockGarageModeService)
                .setPowerPolicyDaemon(powerPolicyDaemon)
                .setDoPriorityInitInConstruction(false)
                .setTestStaticBinder(mFakeStaticBinderInterface)
                .build();

        carImpl.setSystemServerConnections(mICarServiceHelper, new CarServiceConnectedCallback());
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

    @Test
    public void testGetCarService_CarAudioService_CallsWaitForInitComplete_true() throws Exception {
        IInterface powerPolicyDaemon = getMockPowerPolicyDaemon();
        ICarImpl carImpl = new ICarImpl.Builder()
                .setServiceContext(mContext)
                .setVehicle(mMockVehicle)
                .setVehicleInterfaceName("MockedCar")
                .setSystemInterface(mFakeSystemInterface)
                .setCarWatchdogService(mMockCarWatchdogService)
                .setCarPerformanceService(mMockCarPerformanceService)
                .setCarTelemetryService(mMockCarTelemetryService)
                .setCarRemoteAccessServiceConstructor((
                        Context context, SystemInterface systemInterface,
                        PowerHalService powerHalService
                    ) -> mMockCarRemoteAccessService)
                .setGarageModeService(mMockGarageModeService)
                .setCarAudioService(mMockCarAudioService)
                .setCarUserService(mMockCarUserService)
                .setPowerPolicyDaemon(powerPolicyDaemon)
                .setDoPriorityInitInConstruction(false)
                .setTestStaticBinder(mFakeStaticBinderInterface)
                .build();
        when(mMockCarAudioService.waitForInitComplete(anyInt())).thenReturn(true);

        carImpl.init();

        try {
            verify(mMockCarAudioService).init();

            assertThat(carImpl.getCarService(Car.AUDIO_SERVICE)).isEqualTo(mMockCarAudioService);
        } finally {
            carImpl.release();
        }
    }

    @Test
    public void testGetCarService_CarAudioService_CallsWaitForInitComplete_false()
            throws Exception {
        IInterface powerPolicyDaemon = getMockPowerPolicyDaemon();
        ICarImpl carImpl = new ICarImpl.Builder()
                .setServiceContext(mContext)
                .setVehicle(mMockVehicle)
                .setVehicleInterfaceName("MockedCar")
                .setSystemInterface(mFakeSystemInterface)
                .setCarWatchdogService(mMockCarWatchdogService)
                .setCarPerformanceService(mMockCarPerformanceService)
                .setCarTelemetryService(mMockCarTelemetryService)
                .setCarRemoteAccessServiceConstructor((
                        Context context, SystemInterface systemInterface,
                        PowerHalService powerHalService
                    ) -> mMockCarRemoteAccessService)
                .setGarageModeService(mMockGarageModeService)
                .setCarAudioService(mMockCarAudioService)
                .setCarUserService(mMockCarUserService)
                .setPowerPolicyDaemon(powerPolicyDaemon)
                .setDoPriorityInitInConstruction(false)
                .setTestStaticBinder(mFakeStaticBinderInterface)
                .build();

        when(mMockCarAudioService.waitForInitComplete(anyInt())).thenReturn(false);

        carImpl.init();

        try {
            assertThat(carImpl.getCarService(Car.AUDIO_SERVICE)).isNull();
        } finally {
            carImpl.release();
        }
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
