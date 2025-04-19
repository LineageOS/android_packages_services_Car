/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.android.car.internal.SystemConstants.ICAR_SYSTEM_SERVER_CLIENT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.time.TimeManager;
import android.car.Car;
import android.car.ICarResultReceiver;
import android.car.builtin.os.UserManagerHelper;
import android.car.builtin.util.TimingsTraceLog;
import android.car.test.NoActiveHandlerThreadCheckerRule;
import android.car.user.CarUserManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.car.admin.CarDevicePolicyService;
import com.android.car.am.CarActivityService;
import com.android.car.am.FixedActivityService;
import com.android.car.audio.CarAudioService;
import com.android.car.bluetooth.CarBluetoothService;
import com.android.car.cluster.ClusterHomeService;
import com.android.car.cluster.ClusterNavigationService;
import com.android.car.cluster.InstrumentClusterService;
import com.android.car.evs.CarEvsService;
import com.android.car.garagemode.GarageModeService;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.ICarSystemServerClient;
import com.android.car.internal.NotificationHelperBase;
import com.android.car.internal.StaticBinderInterface;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.occupantconnection.CarOccupantConnectionService;
import com.android.car.occupantconnection.CarRemoteDeviceService;
import com.android.car.oem.CarOemProxyService;
import com.android.car.os.CarPerformanceService;
import com.android.car.pm.CarPackageManagerService;
import com.android.car.power.CarPowerManagementService;
import com.android.car.provider.Settings;
import com.android.car.remoteaccess.CarRemoteAccessService;
import com.android.car.stats.CarStatsService;
import com.android.car.systeminterface.ActivityManagerInterface;
import com.android.car.systeminterface.DisplayInterface;
import com.android.car.systeminterface.IOInterface;
import com.android.car.systeminterface.StorageMonitoringInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.TimeInterface;
import com.android.car.systeminterface.WakeLockInterface;
import com.android.car.systemui.keyguard.ExperimentalCarKeyguardService;
import com.android.car.telemetry.CarTelemetryService;
import com.android.car.test.utils.TemporaryDirectory;
import com.android.car.user.CarUserNoticeService;
import com.android.car.user.CarUserService;
import com.android.car.vms.VmsBrokerService;
import com.android.car.watchdog.CarWatchdogService;
import com.android.car.wifi.CarWifiService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link ICarImpl}.
 *
 * All car services are mocked.
 */
@RunWith(MockitoJUnitRunner.class)
public final class ICarImplUnitTest {
    private static final String TAG = ICarImplUnitTest.class.getSimpleName();

    @Rule
    public NoActiveHandlerThreadCheckerRule mNoActiveHandlerThreadCheckerRule =
            new NoActiveHandlerThreadCheckerRule();

    @Mock private ActivityManagerInterface mMockActivityManagerInterface;
    @Mock private DisplayInterface mMockDisplayInterface;
    @Mock private VehicleStub mMockVehicle;
    @Mock private StorageMonitoringInterface mMockStorageMonitoringInterface;
    @Mock private SystemStateInterface mMockSystemStateInterface;
    @Mock private TimeInterface mMockTimeInterface;
    @Mock private WakeLockInterface mMockWakeLockInterface;
    @Mock private ICarServiceHelper mICarServiceHelper;
    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private TimeManager mTimeManager;
    @Mock private Resources mResources;

    @Mock private CarUserService mMockCarUserService;
    @Mock private CarAudioService mMockCarAudioService;
    @Mock private CarFeatureController mMockCarFeatureController;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private SystemActivityMonitoringService mMockSAMService;

    private static final Class<?>[] SERVICE_CLASSES_TO_MOCK = new Class<?>[]{
            CarOemProxyService.class,
            CarPropertyService.class,
            CarDrivingStateService.class,
            CarOccupantZoneService.class,
            CarUxRestrictionsManagerService.class,
            CarActivityService.class,
            CarPackageManagerService.class,
            ExperimentalCarKeyguardService.class,
            CarUserNoticeService.class,
            OccupantAwarenessService.class,
            CarPerUserServiceHelper.class,
            CarBluetoothService.class,
            CarInputService.class,
            CarProjectionService.class,
            GarageModeService.class,
            AppFocusService.class,
            CarNightService.class,
            FixedActivityService.class,
            ClusterNavigationService.class,
            InstrumentClusterService.class,
            CarStatsService.class,
            VmsBrokerService.class,
            CarDiagnosticService.class,
            CarStorageMonitoringService.class,
            CarLocationService.class,
            CarMediaService.class,
            CarBugreportManagerService.class,
            CarWatchdogService.class,
            CarPerformanceService.class,
            CarDevicePolicyService.class,
            ClusterHomeService.class,
            CarEvsService.class,
            CarTelemetryService.class,
            CarRemoteAccessService.class,
            CarWifiService.class,
            CarRemoteDeviceService.class,
            CarOccupantConnectionService.class,
            CarExperimentalFeatureServiceController.class
    };

    private SystemInterface mFakeSystemInterface;

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

    @Before
    public void setUp() throws Exception {
        // It is possible that some other tests run before this one and left some services
        // registered in the global registry. We need to clean them up before setting up this test.
        CarLocalServices.removeAllServices();

        // Used in ICarImpl.
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        // Used in VehicleHal constructor -> TimeHalService constructor
        when(mContext.getSystemService(TimeManager.class)).thenReturn(mTimeManager);

        when(mContext.getResources()).thenReturn(mResources);

        mFakeSystemInterface = SystemInterface.Builder.newSystemInterface()
                .withSystemStateInterface(mMockSystemStateInterface)
                .withActivityManagerInterface(mMockActivityManagerInterface)
                .withDisplayInterface(mMockDisplayInterface)
                .withIOInterface(mMockIOInterface)
                .withStorageMonitoringInterface(mMockStorageMonitoringInterface)
                .withTimeInterface(mMockTimeInterface)
                .withSettings(new Settings.DefaultImpl())
                .withWakeLockInterface(mMockWakeLockInterface).build();

        when(mMockVehicle.getHalPropValueBuilder()).thenReturn(
                new HalPropValueBuilder(/* isAidl= */ true));
        when(mMockCarFeatureController.isFeatureEnabled(any())).thenReturn(true);
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

    private ICarImpl.Builder getBaseICarImplBuilder() {
        var builder = new ICarImpl.Builder()
                .setServiceContext(mContext)
                .setVehicle(mMockVehicle)
                .setVehicleInterfaceName("MockedCar")
                .setSystemInterface(mFakeSystemInterface)
                .setDoPriorityInitInConstruction(false)
                .setTestStaticBinder(mFakeStaticBinderInterface);
        for (int i = 0; i < SERVICE_CLASSES_TO_MOCK.length; i++) {
            var clazz = SERVICE_CLASSES_TO_MOCK[i];
            builder.setInjectedService(clazz, mock(clazz));
        }
        builder.setInjectedService(CarFeatureController.class, mMockCarFeatureController)
                .setInjectedService(CarAudioService.class, mMockCarAudioService)
                .setInjectedService(CarUserService.class, mMockCarUserService)
                .setInjectedService(CarPowerManagementService.class,
                        mMockCarPowerManagementService)
                .setInjectedService(SystemActivityMonitoringService.class, mMockSAMService);
        return builder;
    }

    @Test
    public void testGetCarService_CarAudioService_CallsWaitForInitComplete_true() throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        when(mMockCarAudioService.waitForInitComplete(anyInt())).thenReturn(true);

        carImpl.init();

        try {
            verify(mMockCarAudioService).init();

            assertThat(carImpl.getCarService(Car.AUDIO_SERVICE)).isEqualTo(mMockCarAudioService);
        } finally {
            carImpl.release();
            carImpl.destroy();
        }
    }

    @Test
    public void testGetCarService_CarAudioService_CallsWaitForInitComplete_false()
            throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();

        when(mMockCarAudioService.waitForInitComplete(anyInt())).thenReturn(false);

        carImpl.init();

        try {
            assertThat(carImpl.getCarService(Car.AUDIO_SERVICE)).isNull();
        } finally {
            carImpl.release();
            carImpl.destroy();
        }
    }

    @Test
    public void testGetCarService_CarAudioService_CallsWaitForInitComplete_interrupted()
            throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        when(mMockCarAudioService.waitForInitComplete(anyInt())).thenThrow(
                new InterruptedException());

        carImpl.init();

        boolean interrupted;

        try {
            assertThat(carImpl.getCarService(Car.AUDIO_SERVICE)).isNull();
        } finally {
            // This also clears the interrupt flag.
            interrupted = Thread.interrupted();

            carImpl.release();
            carImpl.destroy();
        }

        assertThat(interrupted).isTrue();
    }

    @Test
    public void testSetSystemServerConnections_notCallingFromSystemProcess() throws Exception {
        StaticBinderInterface mockStaticBinder = mock(StaticBinderInterface.class);
        ICarResultReceiver.Stub carResultReceiver = mock(ICarResultReceiver.Stub.class);
        ICarImpl carImpl = getBaseICarImplBuilder().setTestStaticBinder(mockStaticBinder).build();

        when(mockStaticBinder.getCallingPid()).thenReturn(123);
        when(mockStaticBinder.getCallingUid()).thenReturn(Process.SYSTEM_UID + 1);

        carImpl.setSystemServerConnections(mICarServiceHelper, carResultReceiver);

        try {
            // Verifies that our receiver receives null as bundle.
            verify(carResultReceiver).send(eq(0), eq(null));
        } finally {
            carImpl.destroy();
        }
    }

    @Test
    public void testGetCarManagerClassForFeature() throws Exception {
        var mockCarExpFeatureServiceController = mock(
                CarExperimentalFeatureServiceController.class);
        ICarImpl carImpl = getBaseICarImplBuilder().setIsUserBuild(false)
                .setCarExperimentalFeatureServiceController(mockCarExpFeatureServiceController)
                .build();
        String testFeature = "testFeature";
        String testClass = "testClass";

        when(mockCarExpFeatureServiceController.getCarManagerClassForFeature(testFeature))
                .thenReturn(testClass);

        try {
            assertThat(carImpl.getCarManagerClassForFeature(testFeature)).isEqualTo(testClass);
        } finally {
            carImpl.destroy();
        }
    }

    @Test
    public void testGetCarManagerClassForFeature_userBuildMustReturnNull() throws Exception {
        var mockCarExpFeatureServiceController = mock(
                CarExperimentalFeatureServiceController.class);
        ICarImpl carImpl = getBaseICarImplBuilder().setIsUserBuild(true)
                .setCarExperimentalFeatureServiceController(mockCarExpFeatureServiceController)
                .build();
        String testFeature = "testFeature";
        String testClass = "testClass";

        when(mockCarExpFeatureServiceController.getCarManagerClassForFeature(testFeature))
                .thenReturn(testClass);

        try {
            assertThat(carImpl.getCarManagerClassForFeature(testFeature)).isNull();
        } finally {
            carImpl.destroy();
        }
    }

    @Test
    public void testConstructWithTrace() throws Exception {
        var mockTimingsTraceLog = mock(TimingsTraceLog.class);
        List<CarSystemService> allServices = new ArrayList<>();

        TestCarService testCarService = ICarImpl.constructWithTrace(mockTimingsTraceLog,
                TestCarService.class, () -> new TestCarService(), allServices);

        assertThat(allServices).containsExactly(testCarService);
    }

    @Test
    public void testConstructWithTrace_exceptionThrownInConstructor() throws Exception {
        var mockTimingsTraceLog = mock(TimingsTraceLog.class);
        List<CarSystemService> allServices = new ArrayList<>();

        assertThrows(RuntimeException.class, () -> ICarImpl.constructWithTrace(mockTimingsTraceLog,
                TestCarService.class, () -> {
                    throw new IllegalStateException();
                }, allServices));
    }

    /**
     * Simulates that system server is connected and we passes a {@link ICarSystemServerClient}
     * binder connection to system server.
     */
    private ICarSystemServerClient prepareCarSystemServerClient(ICarImpl carImpl) throws Exception {
        ICarResultReceiver.Stub carResultReceiver = mock(ICarResultReceiver.Stub.class);
        var bundleCaptor = new Bundle[1];
        doAnswer((inv) -> {
            bundleCaptor[0] = (Bundle) inv.getArgument(1);
            return null;
        }).when(carResultReceiver).send(anyInt(), any());

        carImpl.setSystemServerConnections(mICarServiceHelper, carResultReceiver);

        verify(carResultReceiver).send(anyInt(), any());

        var bundle = bundleCaptor[0];
        IBinder carSystemServerClientBinder = bundle.getBinder(ICAR_SYSTEM_SERVER_CLIENT);
        return ICarSystemServerClient.Stub.asInterface(carSystemServerClientBinder);
    }

    @Test
    public void testCarSystemServerClientImpl_onUserLifecycleEvent() throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        ICarSystemServerClient carSystemServerClient = prepareCarSystemServerClient(carImpl);

        int eventType = CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
        int fromUserId = 1;
        int toUserId = 2;

        carSystemServerClient.onUserLifecycleEvent(eventType, fromUserId, toUserId);

        try {
            verify(mMockCarUserService).onUserLifecycleEvent(eventType, fromUserId, toUserId);
        } finally {
            carImpl.destroy();
        }
    }

    /**
     * A fake implementation for NotificationHelper. we want to avoid using the real implementation
     * here because there are system dependencies required for real impl.
     */
    public static final class FakeNotificationHelper extends NotificationHelperBase {
        public FakeNotificationHelper(Context context) {
            super(context);
        }

        @Override
        public void showFactoryResetNotification(ICarResultReceiver callback) {}
    }

    // A fake class loader that loads NotificationHelper.
    static final class FakeClassLoader extends ClassLoader {
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals("com.android.car.admin.NotificationHelper")) {
                throw new ClassNotFoundException();
            }

            return FakeNotificationHelper.class;
        }
    }

    @Test
    public void testCarSystemServerClientImpl_onFactoryReset() throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        ICarSystemServerClient carSystemServerClient = prepareCarSystemServerClient(carImpl);
        ICarResultReceiver carResultReceiver = mock(ICarResultReceiver.class);
        when(mContext.getClassLoader()).thenReturn(new FakeClassLoader());

        carSystemServerClient.onFactoryReset(carResultReceiver);

        try {
            verify(mMockCarPowerManagementService).setFactoryResetCallback(carResultReceiver);
        } finally {
            carImpl.destroy();
        }
    }

    @Test
    public void testCarSystemServerClientImpl_setInitialUser() throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        ICarSystemServerClient carSystemServerClient = prepareCarSystemServerClient(carImpl);
        var user = new UserHandle(UserManagerHelper.USER_SYSTEM);

        carSystemServerClient.setInitialUser(user);

        try {
            verify(mMockCarUserService).setInitialUserFromSystemServer(user);
        } finally {
            carImpl.destroy();
        }
    }

    @Test
    public void testCarSystemServerClientImpl_notifyFocusChanged() throws Exception {
        ICarImpl carImpl = getBaseICarImplBuilder().build();
        ICarSystemServerClient carSystemServerClient = prepareCarSystemServerClient(carImpl);
        int testPid = 123;
        int testUid = 321;

        carSystemServerClient.notifyFocusChanged(testPid, testUid);

        try {
            verify(mMockSAMService).handleFocusChanged(testPid, testUid);
        } finally {
            carImpl.destroy();
        }
    }

    static final class TestCarService implements CarSystemService {
        @Override
        public void init() {}
        @Override
        public void release() {}
        @Override
        public void dump(IndentingPrintWriter writer) {}
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
