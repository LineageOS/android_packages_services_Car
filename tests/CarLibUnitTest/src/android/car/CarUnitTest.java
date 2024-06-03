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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.car.Car.CarBuilder;
import android.car.Car.CarBuilder.ServiceManager;
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
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Pair;

import com.android.car.internal.ICarServiceHelper;
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
    private static final int DEFAULT_TIMEOUT_MS = 1000;

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
    private ICarProperty.Stub mICarProperty;
    @Mock
    private ApplicationInfo mApplicationInfo;

    private HandlerThread mEventHandlerThread;
    private Handler mEventHandler;
    private Handler mMainHandler;
    private CarBuilder mCarBuilder;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final List<ServiceConnection> mBindServiceConnections = new ArrayList<>();
    @GuardedBy("mLock")
    private boolean mCarServiceRegistered;

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
    public void setUp() {
        mEventHandlerThread = new HandlerThread("CarTestEvent");
        mEventHandlerThread.start();
        mEventHandler = new Handler(mEventHandlerThread.getLooper());
        mMainHandler = new Handler(Looper.getMainLooper());
        // Inject mServiceManager as a dependency for creating Car.
        mCarBuilder = new CarBuilder().setServiceManager(mServiceManager);

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
    }

    private void setupFakeServiceManager() {
        when(mContext.bindService(any(), any(), anyInt())).thenAnswer((inv) -> {
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
        }).when(mContext).unbindService(any());

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
        }
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler() {
        Car car = Car.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNotNull();

        car.connect();

        assertThat(car.isConnecting()).isTrue();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_DefaultHandler() {
        Car car = Car.createCar(mContext, mServiceConnectionListener);

        assertThat(car).isNotNull();

        car.connect();

        assertThat(car.isConnecting()).isTrue();
        assertThat(car.isConnected()).isFalse();

        setCarServiceRegistered();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_CarServiceRegistered() {
        setCarServiceRegistered();

        Car car = Car.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNotNull();

        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
        assertThat(car.isConnected()).isTrue();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_Disconnect_Reconnect() {
        setCarServiceRegistered();

        Car car = Car.createCar(mContext, mServiceConnectionListener, mEventHandler);
        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
        clearInvocations(mServiceConnectionListener);

        car.disconnect();
        car.connect();

        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_Disconnect_IgnoreCallback() {
        Car car = Car.createCar(mContext, mServiceConnectionListener, mEventHandler);
        car.connect();
        car.disconnect();

        setCarServiceRegistered();

        // Callback must not be invoked while car is disconnected.
        verify(mServiceConnectionListener, after(DEFAULT_TIMEOUT_MS).never()).onServiceConnected(
                mCarServiceComponentName, mService);

        car.connect();

        // Callback should be invoked after connect again.
        verify(mServiceConnectionListener, timeout(DEFAULT_TIMEOUT_MS)).onServiceConnected(
                mCarServiceComponentName, mService);
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_ContextIsNull() {
        assertThrows(NullPointerException.class, () -> Car.createCar(
                /* context= */ null, mServiceConnectionListener, mEventHandler));
    }

    @Test
    public void testCreateCar_Context_ServiceConnection_Handler_NoAutoFeature() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(false);

        Car car = Car.createCar(mContext, mServiceConnectionListener, mEventHandler);

        assertThat(car).isNull();
    }

    @Test
    public void testCreateCar_Context_CarServiceRegistered() throws Exception {
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
    public void testCreateCar_Context_CarServiceRegistered_DisconnectReconnect() throws Exception {
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
        long currentTimeMs = SystemClock.elapsedRealtime();
        long timeout = currentTimeMs + DEFAULT_TIMEOUT_MS;
        while (!car.isConnected() && SystemClock.elapsedRealtime() < timeout) {
            Thread.sleep(100);
        }

        assertThat(car.isConnected()).isTrue();
    }

    @Test
    public void testCreateCar_Context_CarServiceNeverRegistered_Timeout() {
        // This should timeout.
        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNull();
    }

    @Test
    public void testCreateCar_Context_CarServiceRegisteredLater_BeforeTimeout() throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        // This should block until car service is registered.
        Car car = mCarBuilder.createCar(mContext);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();
        verify(mContext).bindService(any(), any(), anyInt());

        // In the legacy implementation, createCar will bind to car service and cause an
        // onServiceConnected callback to be invoked later. We must make sure this callback is
        // invoked before disconnect, otherwise, the callback will set isConnected to true again.
        finishTasksOnMain();

        car.disconnect();
        assertThat(car.isConnected()).isFalse();
    }

    @Test
    public void testCreateCar_Context_InvokeFromMain() throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            // This should block until car service is registered.
            Car car = mCarBuilder.createCar(mContext);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext).bindService(any(), any(), anyInt());

            car.disconnect();
            assertThat(car.isConnected()).isFalse();
        });
    }

    @Test
    public void testCreateCar_Context_WaitForever_Lclistener_CarServiceRegisteredLater()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();
        verify(mContext).bindService(any(), any(), anyInt());
        mLifecycleListener.assertOneListenerCallAndClear(car, true);

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
    public void testCreateCar_Context_WaitForever_Lclistener_ConnectCrashRestart()
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
    public void testCreateCar_Context_WaitForever_Lclistener_CarServiceAlreadyRegistered()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext, times(1)).bindService(any(), any(), anyInt());

            // mLifecycleListener should have been called as this is main thread.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    public void testCreateCar_Context_WaitForever_Lclistener_ManagerNotTheSameAfterReconnect()
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
    public void testCreateCar_Context_DoNotWait_CarServiceRegistered()
            throws Exception {
        setCarServiceRegistered();

        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isTrue();
        verify(mContext).bindService(any(), any(), anyInt());

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    public void testCreateCar_Context_DoNotWait_CarServiceCrash_Restore()
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
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarServiceRegistered()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext).bindService(any(), any(), anyInt());
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    public void testCreateCar_Context_DoNotWait_CarServiceRegisteredLater()
            throws Exception {
        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isFalse();
        verify(mContext).bindService(any(), any(), anyInt());

        setCarServiceRegistered();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    public void testCreateCar_Context_DoNotWait_CarServiceRegisteredAfterDisconnect()
            throws Exception {
        Car car = mCarBuilder.createCar(mContext, null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isFalse();
        verify(mContext).bindService(any(), any(), anyInt());

        car.disconnect();

        // Car service is registered after disconnect, must not invoke callback.
        setCarServiceRegistered();

        Thread.sleep(DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertNoEvent();

        // After connect, the callback must be invoked.
        car.connect();

        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
    }

    @Test
    public void testCreateCar_Context_DoNotWait_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        setCarServiceRegistered();

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext).bindService(any(), any(), anyInt());
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    public void testCreateCar_Context_WithTimeout_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null, DEFAULT_TIMEOUT_MS, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext).bindService(any(), any(), anyInt());
            // createCar is called from main handler, so callback must have already been called.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    @Test
    public void testCreateCar_Context_WithTimeout_CarServiceRegisteredAfterTimeout()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        Car car = mCarBuilder.createCar(mContext, null, 50, mLifecycleListener);
        assertThat(car).isNotNull();
        assertThat(car.isConnected()).isFalse();
        verify(mContext).bindService(any(), any(), anyInt());

        // The callback should be invoked after 200ms.
        mLifecycleListener.waitForEvent(1, DEFAULT_TIMEOUT_MS);
        mLifecycleListener.assertOneListenerCallAndClear(car, true);
        assertThat(car.isConnected()).isTrue();
    }

    @Test
    public void testCreateCar_Context_WaitForever_InvokeFromMain_CarServiceRegisteredLater()
            throws Exception {
        // Car service is registered after 200ms.
        mEventHandler.postDelayed(() -> setCarServiceRegistered(), 200);

        runOnMain(() -> {
            Car car = mCarBuilder.createCar(mContext, null,
                    Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mLifecycleListener);

            assertThat(car).isNotNull();
            assertThat(car.isConnected()).isTrue();
            verify(mContext, times(1)).bindService(any(), any(), anyInt());

            // mLifecycleListener should have been called as this is main thread.
            mLifecycleListener.assertOneListenerCallAndClear(car, true);
        });
    }

    private void runOnMain(Runnable runnable) throws InterruptedException {
        var cdLatch = new CountDownLatch(1);
        mMainHandler.post(() -> {
            runnable.run();
            cdLatch.countDown();
        });
        cdLatch.await(DEFAULT_TIMEOUT_MS, MILLISECONDS);
    }

    private void finishTasksOnMain() throws InterruptedException {
        // Do nothing on main just to make sure main finished handling the callbacks.
        runOnMain(() -> {});
    }
}
