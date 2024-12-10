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

package android.car;

import static android.car.Car.CAR_SERVICE_BINDER_SERVICE_NAME;
import static android.car.feature.Flags.FLAG_DISPLAY_COMPATIBILITY;
import static android.car.feature.Flags.FLAG_PERSIST_AP_SETTINGS;
import static android.car.feature.Flags.FLAG_CREATE_CAR_USE_NOTIFICATIONS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.Activity;
import android.app.Service;
import android.car.Car.CarBuilder;
import android.car.Car.Deps;
import android.car.builtin.os.ServiceManagerHelper.IServiceRegistrationCallback;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarProperty;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Pair;

import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.os.Process;
import com.android.car.internal.os.ServiceManager;
import com.android.internal.annotations.GuardedBy;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Unit test for Car API.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
@EnableFlags({FLAG_PERSIST_AP_SETTINGS, FLAG_DISPLAY_COMPATIBILITY})
public final class CarUnitTest {

    private static final String TAG = CarUnitTest.class.getSimpleName();
    private static final String PKG_NAME = "Bond.James.Bond";
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SLEEP_MS = 1_000;
    private static final int MY_PID = 1234;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().setProvideMainThread(true)
            .build();

    @Mock
    private Context mContext;
    @Mock
    private ServiceConnection mServiceConnectionListener;
    @Mock
    private ComponentName mCarServiceComponentName;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ServiceManager mServiceManager;
    @Mock
    private Process mFakeProcess;
    @Mock
    private ICarProperty.Stub mICarProperty;
    @Mock
    private ApplicationInfo mApplicationInfo;

    private HandlerThread mEventHandlerThread;
    private Handler mEventHandler;
    private Handler mMainHandler;
    private HandlerThread mServiceManagerHandlerThread;
    private Handler mServiceManagerHandler;
    private CarBuilder mCarBuilder;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final List<ServiceConnection> mBindServiceConnections = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<IServiceRegistrationCallback> mServiceCallbacks = new ArrayList<>();
    @GuardedBy("mLock")
    private boolean mCarServiceRegistered;
    @GuardedBy("mLock")
    private IBinder.DeathRecipient mDeathRecipient;

    // It is tricky to mock this. So create placeholder version instead.
    private final class FakeService extends ICar.Stub {

        @Override
        public void setSystemServerConnections(ICarServiceHelper helper,
                ICarResultReceiver receiver) throws RemoteException {
        }

        @Override
        public boolean isFeatureEnabled(String featureName) {
            return false;
        }

        @Override
        public int enableFeature(String featureName) {
            return Car.FEATURE_REQUEST_SUCCESS;
        }

        @Override
        public int disableFeature(String featureName) {
            return Car.FEATURE_REQUEST_SUCCESS;
        }

        @Override
        public List<String> getAllEnabledFeatures() {
            return Collections.EMPTY_LIST;
        }

        @Override
        public List<String> getAllPendingDisabledFeatures() {
            return Collections.EMPTY_LIST;
        }

        @Override
        public List<String> getAllPendingEnabledFeatures() {
            return Collections.EMPTY_LIST;
        }

        @Override
        public String getCarManagerClassForFeature(String featureName) {
            return null;
        }

        @Override
        public IBinder getCarService(java.lang.String serviceName) {
            if (serviceName.equals(Car.PROPERTY_SERVICE)) {
                return mICarProperty;
            }
            return null;
        }

        @Override
        public int getCarConnectionType() {
            return 0;
        }

        @Override
        public void linkToDeath(IBinder.DeathRecipient deathRecipient, int flags) {
            synchronized (mLock) {
                mDeathRecipient = deathRecipient;
            }
        }
    };

    private final FakeService mService = new FakeService();

    private static final class LifecycleListener implements Car.CarServiceLifecycleListener {
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final ArrayList<Pair<Car, Boolean>> mEvents = new ArrayList<>();

        @Override
        public void onLifecycleChanged(Car car, boolean ready) {
            synchronized (mLock) {
                assertThat(Looper.getMainLooper()).isEqualTo(Looper.myLooper());
                mEvents.add(new Pair<>(car, ready));
                mLock.notifyAll();
            }
        }

        void waitForEvent(int count, int timeoutInMs) throws InterruptedException {
            synchronized (mLock) {
                while (mEvents.size() < count) {
                    mLock.wait(timeoutInMs);
                }
            }
        }

        void assertOneListenerCallAndClear(Car expectedCar, boolean ready) {
            synchronized (mLock) {
                assertThat(mEvents).containsExactly(new Pair<>(expectedCar, ready));
                mEvents.clear();
            }
        }

        void assertNoEvent() {
            synchronized (mLock) {
                assertThat(mEvents).isEmpty();
            }
        }
    }

    private final LifecycleListener mLifecycleListener = new LifecycleListener();

    @Before
    public void setUp() throws Exception {
        mEventHandlerThread = new HandlerThread("CarTestEvent");
        mEventHandlerThread.start();
        mEventHandler = new Handler(mEventHandlerThread.getLooper());
        mServiceManagerHandlerThread = new HandlerThread("CarTestEvent");
        mServiceManagerHandlerThread.start();
        mServiceManagerHandler = new Handler(mServiceManagerHandlerThread.getLooper());
        mMainHandler = new Handler(Looper.getMainLooper());
        // Inject fake dependencies.
        mCarBuilder = new CarBuilder().setFakeDeps(new Deps(
                mServiceManager, mFakeProcess, /* carServiceBindRetryIntervalMs= */ 10,
                /* carServiceBindMaxRetry= */ 2));
        when(mFakeProcess.myPid()).thenReturn(MY_PID);

        when(mContext.getPackageName()).thenReturn(PKG_NAME);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(true);
        setupFakeServiceManager();

        // Setup context for CarPropertyManager
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
    }

    @After
    public void tearDown() {
        mEventHandlerThread.quitSafely();
        mServiceManagerHandlerThread.quitSafely();
    }

    private void setupFakeServiceManager() throws Exception {
        setupFakeServiceManager(mContext);
    }

    private void setupFakeServiceManager(Context context) throws Exception {
        when(context.bindService(any(), any(), anyInt())).thenAnswer((inv) -> {
            ServiceConnection serviceConnection = inv.getArgument(1);

            synchronized (mLock) {
                if (mCarServiceRegistered) {
                    mMainHandler.post(() -> serviceConnection.onServiceConnected(
                            mCarServiceComponentName,  mService));
                }
                mBindServiceConnections.add(serviceConnection);
            }

            return true;
        });

        doAnswer((inv) -> {
            ServiceConnection serviceConnection = inv.getArgument(0);

            synchronized (mLock) {
                mBindServiceConnections.remove(serviceConnection);
            }
            return null;
        }).when(context).unbindService(any());

        doAnswer((inv) -> {
            synchronized (mLock) {
                if (mCarServiceRegistered) {
                    ((IServiceRegistrationCallback) inv.getArgument(1))
                            .onRegistration(CAR_SERVICE_BINDER_SERVICE_NAME, mService);
                }
                mServiceCallbacks.add(inv.getArgument(1));
            }
            return null;
        }).when(mServiceManager).registerForNotifications(
                eq(CAR_SERVICE_BINDER_SERVICE_NAME), any());

        when(mServiceManager.getService(CAR_SERVICE_BINDER_SERVICE_NAME))
                .thenAnswer((inv) -> {
                    synchronized (mLock) {
                        if (mCarServiceRegistered) {
                            return mService;
                        }
                        return null;
                    }
                });
    }

    private void setCarServiceRegistered() {
        synchronized (mLock) {
            mCarServiceRegistered = true;
            for (int i = 0; i < mBindServiceConnections.size(); i++) {
                var serviceConnection = mBindServiceConnections.get(i);
                mMainHandler.post(() -> serviceConnection.onServiceConnected(
                        mCarServiceComponentName, mService));
            }
            for (int i = 0; i < mServiceCallbacks.size(); i++) {
                IServiceRegistrationCallback callback = mServiceCallbacks.get(i);
                mServiceManagerHandler.post(() -> callback.onRegistration(
                        CAR_SERVICE_BINDER_SERVICE_NAME, mService));
            }
        }
    }

    private void setCarServiceDisconnected() {
        synchronized (mLock) {
            mCarServiceRegistered = false;
            for (int i = 0; i < mBindServiceConnections.size(); i++) {
                var serviceConnection = mBindServiceConnections.get(i);
                mMainHandler.post(() -> serviceConnection.onServiceDisconnected(
                        mCarServiceComponentName));
            }
            if (mDeathRecipient != null) {
                // Copy mDeathRecipient to be catpured outside of the lock.
                IBinder.DeathRecipient deathRecipient = mDeathRecipient;
                mServiceManagerHandler.post(() -> deathRecipient.binderDied());
            }
        }
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_oldLogic() {
        createCar_Context_ServiceConnection_Handler();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_newLogic() {
        createCar_Context_ServiceConnection_Handler();
    }

    private void createCar_Context_ServiceConnection_Handler() {
        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNotNull();

        car.connect();

        assertThat(car.isConnecting()).isTrue();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_DefaultHandler_oldLogic() {
        createCar_Context_ServiceConnection_DefaultHandler();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_DefaultHandler_newLogic() {
        createCar_Context_ServiceConnection_DefaultHandler();
    }

    private void createCar_Context_ServiceConnection_DefaultHandler() {
        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener);

        assertThat(car).isNotNull();

        car.connect();

        assertThat(car.isConnecting()).isTrue();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_NullServiceConnection_DefaultHandler_oldLogic()
            throws Exception {
        createCar_Context_NullServiceConnection_DefaultHandler();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_NullServiceConnection_DefaultHandler_newLogic()
            throws Exception {
        createCar_Context_NullServiceConnection_DefaultHandler();
    }

    private void createCar_Context_NullServiceConnection_DefaultHandler() throws Exception {
        Car car = mCarBuilder.createCar(mContext, (ServiceConnection) null);

        assertThat(car).isNotNull();

        car.connect();

        assertThat(car.isConnecting()).isTrue();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        pollingLoopForCarConnected(car);

        assertThat(car.isConnected()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_CarServiceRegistered_oldLogic() {
        createCar_Context_ServiceConnection_Handler_CarServiceRegistered();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_CarServiceRegistered_newLogic() {
        createCar_Context_ServiceConnection_Handler_CarServiceRegistered();
    }

    private void createCar_Context_ServiceConnection_Handler_CarServiceRegistered() {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNotNull();

        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_Disconnect_Reconnect_oldLogic() {
        createCar_Context_ServiceConnection_Handler_Disconnect_Reconnect();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_ServiceConnection_Handler_Disconnect_Reconnect_newLogic() {
        createCar_Context_ServiceConnection_Handler_Disconnect_Reconnect();
    }

    private void createCar_Context_ServiceConnection_Handler_Disconnect_Reconnect() {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener, mEventHandler);
        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
        clearInvocations(mServiceConnectionListener);

        car.disconnect();
        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_SC_Handler_Disconnect_IgnoreCallback_oldLogic() {
        createCar_Context_ServiceConnection_Handler_Disconnect_IgnoreCallback();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_SC_Handler_Disconnect_IgnoreCallback_newLogic() {
        createCar_Context_ServiceConnection_Handler_Disconnect_IgnoreCallback();
    }

    private void createCar_Context_ServiceConnection_Handler_Disconnect_IgnoreCallback() {
        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener, mEventHandler);
        car.connect();
        car.disconnect();

        setCarServiceRegistered();

        // Callback must not be invoked while car is disconnected.
        verify(mServiceConnectionListener, after(DEFAULT_TIMEOUT_MS).never()).onServiceConnected(
                any(), eq(mService));

        car.connect();

        // Callback should be invoked after connect again.
        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                any(), eq(mService));
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_ContextIsNull() {
        assertThrows(NullPointerException.class, () -> mCarBuilder.createCar(
                /* context= */ null, mServiceConnectionListener, mEventHandler));
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_NoAutoFeature() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);

        Car car = mCarBuilder.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNull();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegistered_oldLogic() throws Exception {
        createCar_Context_CarServiceRegistered();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegistered_newLogic() throws Exception {
        createCar_Context_CarServiceRegistered();
    }

    private void createCar_Context_CarServiceRegistered() throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();

        // In the legacy implementation, createCar will bind to car service and cause an
        // onServiceConnected callback to be invoked later. We must make sure this callback is
        // invoked before disconnect, otherwise, the callback will set isConnected to true again.
        finishTasksOnMain();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegistered_DisconnectReconnect_oldLogic()
            throws Exception {
        createCar_Context_CarServiceRegistered_DisconnectReconnect();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegistered_DisconnectReconnect_newLogic()
            throws Exception {
        createCar_Context_CarServiceRegistered_DisconnectReconnect();
    }

    private void createCar_Context_CarServiceRegistered_DisconnectReconnect() throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();

        // In the legacy implementation, createCar will bind to car service and cause an
        // onServiceConnected callback to be invoked later. We must make sure this callback is
        // invoked before disconnect, otherwise, the callback will set isConnected to true again.
        finishTasksOnMain();

        car.disconnect();
        car.connect();

        // It takes a while for the callback to set connection state to connected.
        pollingLoopForCarConnected(car);

        assertThat(car.isConnected()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceNeverRegistered_Timeout_oldLogic() {
        createCar_Context_CarServiceNeverRegistered_Timeout();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceNeverRegistered_Timeout_newLogic() {
        createCar_Context_CarServiceNeverRegistered_Timeout();
    }

    private void createCar_Context_CarServiceNeverRegistered_Timeout() {
        // This should timeout.
        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNull();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegisteredLater_BeforeTimeout_oldLogic()
            throws Exception {
        createCar_Context_CarServiceRegisteredLater_BeforeTimeout();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceRegisteredLater_BeforeTimeout_newLogic()
            throws Exception {
        createCar_Context_CarServiceRegisteredLater_BeforeTimeout();
    }

    private void createCar_Context_CarServiceRegisteredLater_BeforeTimeout() throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        // This should block until car service is registered.
        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();

        // In the legacy implementation, createCar will bind to car service and cause an
        // onServiceConnected callback to be invoked later. We must make sure this callback is
        // invoked before disconnect, otherwise, the callback will set isConnected to true again.
        finishTasksOnMain();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_InvokeFromMain_oldLogic() throws Exception {
        createCar_Context_InvokeFromMain();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_InvokeFromMain_newLogic() throws Exception {
        createCar_Context_InvokeFromMain();
    }

    private void createCar_Context_InvokeFromMain() throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            // This should block until car service is registered.
            Car car = mCarBuilder.createCar(mContext);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();

            car.disconnect();
            assertThat(car.isConnected()).isFalse();
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_killClient_oldLogic() throws Exception {
        createCar_Context_CarServiceCrash_killClient();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_killClient_newLogic() throws Exception {
        createCar_Context_CarServiceCrash_killClient();
    }

    private void createCar_Context_CarServiceCrash_killClient() throws Exception {
        setCarServiceRegistered();
        mCarBuilder.createCar(mContext);

        // Simulate car service crash.
        setCarServiceDisconnected();

        verify(mFakeProcess, timeout(DEFAULT_TIMEOUT_MS)).killProcess(MY_PID);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_killClientService_oldLogic()
            throws Exception {
        createCar_Context_CarServiceCrash_killClientService();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_killClientService_newLogic()
            throws Exception {
        createCar_Context_CarServiceCrash_killClientService();
    }

    private void createCar_Context_CarServiceCrash_killClientService() throws Exception {
        Service serviceContext = mock(Service.class);
        setupFakeServiceManager(serviceContext);
        setCarServiceRegistered();
        when(serviceContext.getBaseContext()).thenReturn(mContext);
        when(serviceContext.getPackageName()).thenReturn("package");
        mCarBuilder.createCar(serviceContext);

        // Simulate car service crash.
        setCarServiceDisconnected();

        verify(mFakeProcess, timeout(DEFAULT_TIMEOUT_MS)).killProcess(MY_PID);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_stopClientActivity_oldLogic()
            throws Exception {
        createCar_Context_CarServiceCrash_stopClientActivity();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrash_stopClientActivity_newLogic()
            throws Exception {
        createCar_Context_CarServiceCrash_stopClientActivity();
    }

    private void createCar_Context_CarServiceCrash_stopClientActivity() throws Exception {
        Activity activityContext = mock(Activity.class);
        setupFakeServiceManager(activityContext);
        setCarServiceRegistered();
        when(activityContext.getBaseContext()).thenReturn(mContext);
        when(activityContext.isFinishing()).thenReturn(false);
        mCarBuilder.createCar(activityContext);

        // Simulate car service crash.
        setCarServiceDisconnected();

        verify(activityContext, timeout(DEFAULT_TIMEOUT_MS)).finish();
        verify(mFakeProcess, never()).killProcess(anyInt());
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_NullBaseContext_oldLogic() throws Exception {
        createCar_Context_NullBaseContext();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_NullBaseContext_newLogic() throws Exception {
        createCar_Context_NullBaseContext();
    }

    private void createCar_Context_NullBaseContext() throws Exception {
        // Base context is null.
        Service serviceContext = mock(Service.class);

        assertThrows(NullPointerException.class, () -> mCarBuilder.createCar(serviceContext));
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrashAfterDisconnect_oldLogic() throws Exception {
        createCar_Context_CarServiceCrashAfterDisconnect();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_CarServiceCrashAfterDisconnect_newLogic() throws Exception {
        createCar_Context_CarServiceCrashAfterDisconnect();
    }

    private void createCar_Context_CarServiceCrashAfterDisconnect() throws Exception {
        setCarServiceRegistered();
        Car car = mCarBuilder.createCar(mContext);

        // In the legacy implementation, createCar will bind to car service and cause an
        // onServiceConnected callback to be invoked later. We must make sure this callback is
        // invoked before disconnect, otherwise, the callback will set isConnected to true again.
        finishTasksOnMain();
        car.disconnect();

        setCarServiceDisconnected();
        finishTasksOnMain();

        verify(mFakeProcess, never()).killProcess(anyInt());
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater_oldLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater(false);
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater_newLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater(true);
    }

    private void createCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater(
            boolean flagCreateCarUseNotifications) throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();
        if (!flagCreateCarUseNotifications) {
            verify(mContext).bindService(any(), any(), anyInt());
        }
        // The callback will be called from the main thread, so it is not guaranteed to be called
        // after createCar returns.
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);

        if (flagCreateCarUseNotifications) {
            // The rest of the test is for the old logic.
            return;
        }

        // Just call these to guarantee that nothing crashes with these call.
        ServiceConnection serviceConnection;
        synchronized (mLock) {
            serviceConnection = mBindServiceConnections.get(0);
        }
        runOnMain(() -> {
            serviceConnection.onServiceConnected(new ComponentName("", ""), mService);
            serviceConnection.onServiceDisconnected(new ComponentName("", ""));
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_ConnectCrashRestart_oldLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_ConnectCrashRestart();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_ConnectCrashRestart_newLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_ConnectCrashRestart();
    }

    private void createCar_Context_WaitForever_Lclistener_ConnectCrashRestart()
            throws Exception {
        // Car service is registered after 100ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 100);

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();
        // The callback will be called from the main thread, so it is not guaranteed to be called
        // after createCar returns.
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);

        // Fake crash.
        mEventHandler.post(() -> setCarServiceDisconnected());
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);

        mLifecycleListener.assertOneListenerCallAndClear(car, false);
        assertThat(car.isConnected()).isFalse();

        // fake restart
        mEventHandler.post(() -> setCarServiceRegistered());
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);

        mLifecycleListener.assertOneListenerCallAndClear(car, true);
        assertThat(car.isConnected()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_CSAlreadyRegistered_oldLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_CarServiceAlreadyRegistered();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_CSAlreadyRegistered_newLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_CarServiceAlreadyRegistered();
    }

    private void createCar_Context_WaitForever_Lclistener_CarServiceAlreadyRegistered()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();

            // mLifecycleListener should have been called as this is main thread.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_MgrNotTheSameAfterReconnect_oldLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_ManagerNotTheSameAfterReconnect();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_Lclistener_MgrNotTheSameAfterReconnect_newLogic()
            throws Exception {
        createCar_Context_WaitForever_Lclistener_ManagerNotTheSameAfterReconnect();
    }

    private void createCar_Context_WaitForever_Lclistener_ManagerNotTheSameAfterReconnect()
            throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

        CarPropertyManager oldMgr = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);

        // Simulate car service crash.
        setCarServiceDisconnected();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, false);

        // Simulate car service restore.
        setCarServiceRegistered();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
        CarPropertyManager newMgr = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);

        assertThat(oldMgr).isNotEqualTo(newMgr);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceRegistered_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegistered();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceRegistered_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegistered();
    }

    private void createCar_Context_DoNotWait_CarServiceRegistered()
            throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceCrash_Restore_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceCrash_Restore();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceCrash_Restore_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceCrash_Restore();
    }

    private void createCar_Context_DoNotWait_CarServiceCrash_Restore()
            throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);

        // Simulate car service crash.
        setCarServiceDisconnected();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, false);
        assertThat(car.isConnected()).isFalse();

        // Simulate car service restore.
        setCarServiceRegistered();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
        assertThat(car.isConnected()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarSvcRegistered_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegistered();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarSvcRegistered_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegistered();
    }

    private void createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegistered()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceRegisteredLater_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegisteredLater();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarServiceRegisteredLater_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegisteredLater();
    }

    private void createCar_Context_DoNotWait_CarServiceRegisteredLater()
            throws Exception {
        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarSvcRegisteredAfterDisconnect_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegisteredAfterDisconnect();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_CarSvcRegisteredAfterDisconnect_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_CarServiceRegisteredAfterDisconnect();
    }

    private void createCar_Context_DoNotWait_CarServiceRegisteredAfterDisconnect()
            throws Exception {
        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isFalse();

        car.disconnect();

        // Car service is registered after disconnect, must not invoke callback.
        setCarServiceRegistered();

        Thread.sleep(DEFAULT_SLEEP_MS);
        mLifecycleListener.assertNoEvent();

        // After connect, the callback must be invoked.
        car.connect();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarSvcRegisteredLater_oldLogic()
            throws Exception {
        createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegisteredLater();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarSvcRegisteredLater_newLogic()
            throws Exception {
        createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegisteredLater();
    }

    private void createCar_Context_DoNotWait_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WithTimeout_InvokeFromMain_CarSvcRegisteredLater_oldLogic()
            throws Exception {
        createCar_Context_WithTimeout_InvokeFromMain_CarServiceRegisteredLater();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WithTimeout_InvokeFromMain_CarSvcRegisteredLater_newLogic()
            throws Exception {
        createCar_Context_WithTimeout_InvokeFromMain_CarServiceRegisteredLater();
    }

    private void createCar_Context_WithTimeout_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null, DEFAULT_TIMEOUT_MS, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WithTimeout_CarSvcRegisteredAfterTimeout_oldLogic()
            throws Exception {
        createCar_Context_WithTimeout_CarServiceRegisteredAfterTimeout();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WithTimeout_CarSvcRegisteredAfterTimeout_newLogic()
            throws Exception {
        createCar_Context_WithTimeout_CarServiceRegisteredAfterTimeout();
    }

    private void createCar_Context_WithTimeout_CarServiceRegisteredAfterTimeout()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        Car car = mCarBuilder.createCar(mContext, null, /* waitTimeoutMs= */50, mLifecycleListener);
        assertThat(car).isNotNull();

        // The callback should be invoked after 200ms.
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
        assertThat(car.isConnected()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_InvokeFromMain_CarSvcRegisteredLater_oldLogic()
            throws Exception {
        createCar_Context_WaitForever_InvokeFromMain_CarServiceRegisteredLater();
    }

    @Test
    @EnableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_WaitForever_InvokeFromMain_CarSvcRegisteredLater_newLogic()
            throws Exception {
        createCar_Context_WaitForever_InvokeFromMain_CarServiceRegisteredLater();
    }

    /**
     * The following test cases are for old logic only.
     */
    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_bindServiceFailed() throws Exception {
        when(mContext.bindService(any(), any(), anyInt())).thenReturn(false);
        when(mServiceManager.getService(CAR_SERVICE_BINDER_SERVICE_NAME))
                .thenReturn(mService);

        mCarBuilder.createCar(mContext);

        // bindService failures will cause onServiceDisconnected which will kill the client.
        verify(mFakeProcess, timeout(DEFAULT_TIMEOUT_MS)).killProcess(MY_PID);
    }

    @Test
    @DisableFlags(FLAG_CREATE_CAR_USE_NOTIFICATIONS)
    public void testCreateCar_Context_bindService_retry_success() throws Exception {
        when(mContext.bindService(any(), any(), anyInt())).thenReturn(false).thenReturn(true);
        when(mServiceManager.getService(CAR_SERVICE_BINDER_SERVICE_NAME))
                .thenReturn(mService);

        mCarBuilder.createCar(mContext);

        Thread.sleep(DEFAULT_SLEEP_MS);
        verify(mFakeProcess, never()).killProcess(anyInt());
    }

    private void createCar_Context_WaitForever_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();

            // mLifecycleListener should have been called as this is main thread.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    private void runOnMain(Runnable runnable) throws InterruptedException {
        var cdLatch = new CountDownLatch(1);
        // Use a list to be effectively final. We only store one exception.
        List<RuntimeException> exceptions = new ArrayList<>();
        List<AssertionError> assertionErrors = new ArrayList<>();
        mMainHandler.post(() -> {
            try {
                runnable.run();
            } catch (RuntimeException e) {
                exceptions.add(e);
            } catch (AssertionError e) {
                assertionErrors.add(e);
            } finally {
                cdLatch.countDown();
            }
        });
        assertWithMessage("Main thread not finish before: " + DEFAULT_TIMEOUT_MS + " ms").that(
                cdLatch.await(DEFAULT_TIMEOUT_MS, MILLISECONDS)).isTrue();
        // Rethrow the caught errors to fail the test, if any.
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
        if (!assertionErrors.isEmpty()) {
            throw assertionErrors.get(0);
        }
    }

    private void finishTasksOnMain() throws InterruptedException {
        // Do nothing on main just to make sure main finished handling the callbacks.
        runOnMain(() -> {});
    }

    private void pollingLoopForCarConnected(Car car) throws InterruptedException {
        long currentTimeMs = SystemClock.elapsedRealtime();
        long timeout = currentTimeMs + DEFAULT_TIMEOUT_MS;
        while (!car.isConnected() && SystemClock.elapsedRealtime() < timeout) {
            Thread.sleep(100);
        }
    }
}
