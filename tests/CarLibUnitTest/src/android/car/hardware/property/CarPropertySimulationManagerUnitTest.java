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

package android.car.hardware.property;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.builtin.os.BuildHelper;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.test.AbstractExpectableTestCase;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.android.car.internal.os.HandlerExecutor;
import com.android.car.internal.property.CarPropertyConfigList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public class CarPropertySimulationManagerUnitTest extends AbstractExpectableTestCase {

    @Mock
    private ICarProperty mICarProperty;
    @Mock
    private Car mCar;
    @Mock
    private IBinder mBinderMock;
    @Mock
    private Context mContextMock;
    @Mock
    private CarPropertySimulationManager.CarRecorderListener mCarRecorderListener;
    @Mock
    private CarPropertySimulationManager.CarRecorderListener mCarRecorderListener2;
    @Mock private CarPropertyConfig<?> mCarPropertyConfig;
    @Mock private Executor mExecutorMock;
    @Mock private CarPropertyValue<Integer> mCarPropertyValue;

    @Captor private ArgumentCaptor<ICarPropertyEventListener> mListenerCaptor;
    @Captor private ArgumentCaptor<List<CarPropertyValue<?>>> mCarPropertyValueCaptor;
    @Captor private ArgumentCaptor<Runnable> mRunnableCaptor;

    private Handler mMainHandler;
    private Handler mMainHandlerSpy;
    private CarPropertySimulationManager mCarPropertySimulationManager;

    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final int TEST_PROP_ID = 123;
    private static final int TEST_AREA_ID = 0;
    private static final long TEST_TIMESTAMP = 12345L;
    private static final int TEST_VALUE = 42;

    @Before
    public void setUp() throws Exception {
        when(mBinderMock.queryLocalInterface(anyString())).thenReturn(mICarProperty);
        when(mCar.getContext()).thenReturn(mContextMock);

        mMainHandler = new Handler(Looper.getMainLooper());
        mMainHandlerSpy = spy(mMainHandler);

        when(mCar.getEventHandler()).thenReturn(mMainHandlerSpy);
        when(mCar.handleRemoteExceptionFromCarService(any(RemoteException.class), any()))
                .thenAnswer(
                        (inv) -> {
                            return inv.getArgument(1);
                        });
        when(mContextMock.getApplicationContext()).thenReturn(mContextMock);

        mCarPropertySimulationManager = new CarPropertySimulationManager(mCar, mBinderMock);
    }

    @Test
    public void testStartRecordingVehicleProperties_validArguments() throws Exception {
        when(mICarProperty.registerRecordingListener(any())).thenReturn(new
                CarPropertyConfigList(List.of(mCarPropertyConfig)));

        assertWithMessage("Start recording vehicle properties with valid response")
                .that(mCarPropertySimulationManager.startRecordingVehicleProperties(DIRECT_EXECUTOR,
                        mCarRecorderListener)).containsExactly(mCarPropertyConfig);
        expectThat(mCarPropertySimulationManager.getCarRecorderListener())
                .isEqualTo(mCarRecorderListener);
        expectThat(mCarPropertySimulationManager.getCallbackExecutor()).isEqualTo(DIRECT_EXECUTOR);
    }

    @Test
    public void testStartRecordingVehicleProperties_nullExecutor() throws Exception {
        when(mICarProperty.registerRecordingListener(any()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(null, mCarRecorderListener);

        expectThat(mCarPropertySimulationManager.getCarRecorderListener())
                .isEqualTo(mCarRecorderListener);
        expectThat(mCarPropertySimulationManager.getCallbackExecutor())
                .isInstanceOf(HandlerExecutor.class);
    }

    @Test
    public void testStartRecordingVehicleProperties_invalidListener() {
        assertThrows(NullPointerException.class, () -> mCarPropertySimulationManager
                .startRecordingVehicleProperties(DIRECT_EXECUTOR, /* listener= */ null));
    }

    @Test
    public void testStartRecordingVehicleProperties_propertyServiceRemoteException()
            throws Exception {
        RemoteException remoteException = new RemoteException("Register failed!");
        when(mICarProperty.registerRecordingListener(any())).thenThrow(remoteException);

        assertWithMessage("start recording vehicle properties threw")
                .that(mCarPropertySimulationManager.startRecordingVehicleProperties(DIRECT_EXECUTOR,
                        mCarRecorderListener)).isEmpty();
    }

    @Test
    public void testStartRecordingVehiclesProperties_twoCallsSecondFailed() throws Exception {
        when(mICarProperty.registerRecordingListener(any())).thenReturn(new
                CarPropertyConfigList(List.of(mCarPropertyConfig)));
        mCarPropertySimulationManager.startRecordingVehicleProperties(DIRECT_EXECUTOR,
                mCarRecorderListener);
        when(mICarProperty.registerRecordingListener(any())).thenThrow(
                new RemoteException("Register failed!"));

        mCarPropertySimulationManager.startRecordingVehicleProperties(null,
                mCarRecorderListener2);

        expectWithMessage("Original listener")
                .that(mCarPropertySimulationManager.getCarRecorderListener())
                .isEqualTo(mCarRecorderListener);
        expectWithMessage("Original executor")
                .that(mCarPropertySimulationManager.getCallbackExecutor())
                .isEqualTo(DIRECT_EXECUTOR);
    }

    @Test
    public void testStartRecordingVehicleProperties_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).registerRecordingListener(any());

        assertThrows(
                SecurityException.class,
                () ->
                        mCarPropertySimulationManager.startRecordingVehicleProperties(
                                DIRECT_EXECUTOR, mCarRecorderListener));
    }

    @Test
    public void testHandleEvents_listenerCallback() throws Exception {
        when(mICarProperty.registerRecordingListener(mListenerCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(
                DIRECT_EXECUTOR, mCarRecorderListener);
        mListenerCaptor.getValue().onEvent(List.of(new CarPropertyEvent(0, mCarPropertyValue)));

        verify(mCarRecorderListener).onCarPropertyEvents(mCarPropertyValueCaptor.capture());
        assertThat(mCarPropertyValueCaptor.getValue()).containsExactly(mCarPropertyValue);
    }

    @Test
    public void testIsRecordingVehicleProperties_returnsFalse() throws Exception {
        when(mICarProperty.isRecordingVehicleProperties()).thenReturn(false);

        assertWithMessage("call to isRecordingVehicleProeprties")
                .that(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    public void testIsRecordingVehicleProperties_returnsTrue() throws Exception {
        when(mICarProperty.isRecordingVehicleProperties()).thenReturn(true);

        assertWithMessage("call to isRecordingVehicleProeprties")
                .that(mCarPropertySimulationManager.isRecordingVehicleProperties()).isTrue();
    }

    @Test
    public void testIsRecordingVehicleProperties_throws() throws Exception {
        when(mICarProperty.isRecordingVehicleProperties()).thenThrow(new
                RemoteException("isRecordingVehicleProperties failed"));

        assertWithMessage("call to isRecordingVehicleProeprties failed")
                .that(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    public void testIsRecordingVehicleProperties_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).isRecordingVehicleProperties();

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.isRecordingVehicleProperties());
    }

    @Test
    public void testStopRecordingVehicleProperties_success() throws Exception {
        // Start recording first to set up listener and executor
        when(mICarProperty.registerRecordingListener(mListenerCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(
                DIRECT_EXECUTOR, mCarRecorderListener);
        mCarPropertySimulationManager.stopRecordingVehicleProperties();

        verify(mICarProperty).stopRecordingVehicleProperties(mListenerCaptor.getValue());
        expectThat(mCarPropertySimulationManager.getCarRecorderListener()).isNull();
        expectThat(mCarPropertySimulationManager.getCallbackExecutor()).isNull();
        verify(mCarRecorderListener).onRecordingFinished();
    }

    @Test
    public void testStopRecordingVehicleProperties_remoteException() throws Exception {
        doThrow(new RemoteException("stopRecordingVehicleProeprties failed")).when(mICarProperty)
                .stopRecordingVehicleProperties(any());

        mCarPropertySimulationManager.stopRecordingVehicleProperties();

        verify(mICarProperty).stopRecordingVehicleProperties(any());
    }

    @Test
    public void testStopRecordingVehicleProperties_onRecordingFinished_calledOnExecutor()
            throws Exception {
        when(mICarProperty.registerRecordingListener(any()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(
                mExecutorMock, mCarRecorderListener);
        mCarPropertySimulationManager.stopRecordingVehicleProperties();

        verify(mExecutorMock).execute(mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();
        verify(mCarRecorderListener).onRecordingFinished();
    }

    @Test
    public void testStopRecordingVehicleProperties_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).stopRecordingVehicleProperties(any());

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.stopRecordingVehicleProperties());
    }

    @Test
    public void testEnableInjectionMode() throws Exception {
        mCarPropertySimulationManager.enableInjectionMode(List.of(123));

        verify(mICarProperty).enableInjectionMode(eq(new int[] {123}));
    }

    @Test
    public void testEnableInjectionMode_nullInput() {
        assertThrows(
                NullPointerException.class,
                () -> mCarPropertySimulationManager.enableInjectionMode(null));
    }

    @Test
    public void testEnableInjectionMode_emptyList() throws Exception {
        when(mICarProperty.enableInjectionMode(any())).thenReturn(12345L);

        mCarPropertySimulationManager.enableInjectionMode(List.of());

        verify(mICarProperty).enableInjectionMode(eq(new int[] {}));
    }

    @Test
    public void testEnableInjectionMode_remoteException() throws Exception {
        when(mICarProperty.enableInjectionMode(any()))
                .thenThrow(new RemoteException("Enable failed"));

        assertThrows(
                IllegalStateException.class,
                () -> mCarPropertySimulationManager.enableInjectionMode(List.of(123)));
    }

    @Test
    public void testEnableInjectionMode_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).enableInjectionMode(any());

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.enableInjectionMode(List.of(123)));
    }

    @Test
    public void testDisableInjectionMode() throws Exception {
        mCarPropertySimulationManager.disableInjectionMode();

        verify(mICarProperty).disableInjectionMode();
    }

    @Test
    public void testDisableInjectionMode_remoteException() throws Exception {
        doThrow(RemoteException.class).when(mICarProperty).disableInjectionMode();

        // Should not throw, only logs
        mCarPropertySimulationManager.disableInjectionMode();

        verify(mICarProperty).disableInjectionMode();
    }

    @Test
    public void testDisableInjectionMode_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).disableInjectionMode();

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.disableInjectionMode());
    }

    @Test
    public void testIsVehiclePropertyInjectionModeEnabled_withTrue() throws Exception {
        when(mICarProperty.isVehiclePropertyInjectionModeEnabled()).thenReturn(true);

        assertWithMessage("Calling isVehiclePropertyInjectionMode()").that(
                mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isTrue();
    }

    @Test
    public void testIsVehiclePropertyInjectionModeEnabled_withFalse() throws Exception {
        when(mICarProperty.isVehiclePropertyInjectionModeEnabled()).thenReturn(false);

        assertWithMessage("Calling isVehiclePropertyInjectionMode()").that(
                mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
    }

    @Test
    public void testIsVehiclePropertyInjectionModeEnabled_throwsRemoteException() throws Exception {
        when(mICarProperty.isVehiclePropertyInjectionModeEnabled()).thenThrow(new
                RemoteException("isVehiclePropertyInjectionModeEnabled failed"));

        assertWithMessage("Calling isVehiclePropertyInjectionMode()").that(
                mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
    }

    @Test
    public void testIsVehiclePropertyInjectionModeEnabled_permissionDenied() throws Exception {
        doThrow(SecurityException.class)
                .when(mICarProperty)
                .isVehiclePropertyInjectionModeEnabled();

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled());
    }

    @Test
    public void testGetLastInjectedVehicleProperty() throws Exception {
        when(mICarProperty.getLastInjectedVehicleProperty(eq(123))).thenReturn(mCarPropertyValue);

        assertWithMessage("get last injected vehicle property returns value").that(
                mCarPropertySimulationManager.getLastInjectedVehicleProperty(123))
                .isEqualTo(mCarPropertyValue);

        verify(mICarProperty).getLastInjectedVehicleProperty(eq(123));
    }

    @Test
    public void testGetLastInjectedVehicleProperty_returnsNull() throws Exception {
        when(mICarProperty.getLastInjectedVehicleProperty(eq(123))).thenReturn(null);

        assertThat(mCarPropertySimulationManager.getLastInjectedVehicleProperty(123)).isNull();
    }

    @Test
    public void testGetLastInjectedVehicleProperty_remoteException() throws Exception {
        when(mICarProperty.getLastInjectedVehicleProperty(anyInt()))
                .thenThrow(new RemoteException("Get failed"));

        assertThat(mCarPropertySimulationManager.getLastInjectedVehicleProperty(123)).isNull();
    }

    @Test
    public void testGetLastInjectedVehicleProperty_permissionDenied() throws Exception {
        doThrow(SecurityException.class)
                .when(mICarProperty)
                .getLastInjectedVehicleProperty(anyInt());

        assertThrows(
                SecurityException.class,
                () -> mCarPropertySimulationManager.getLastInjectedVehicleProperty(123));
    }

    @Test
    public void testGetLastInjectedVehicleProperty_injectionNotEnabled() throws Exception {
        doThrow(IllegalStateException.class)
                .when(mICarProperty)
                .getLastInjectedVehicleProperty(anyInt());

        assertThrows(
                IllegalStateException.class,
                () -> mCarPropertySimulationManager.getLastInjectedVehicleProperty(123));
    }

    @Test
    public void testInjectVehicleProperties() throws Exception {
        List<CarPropertyValue> carPropertyValueList = List.of(mCarPropertyValue);
        mCarPropertySimulationManager.injectVehicleProperties(carPropertyValueList);

        verify(mICarProperty).injectVehicleProperties(eq(carPropertyValueList));
    }

    @Test
    public void testInjectVehicleProperties_nullInput() {
        assertThrows(
                NullPointerException.class,
                () -> mCarPropertySimulationManager.injectVehicleProperties(null));
    }

    @Test
    public void testInjectVehicleProperties_emptyList() throws Exception {
        mCarPropertySimulationManager.injectVehicleProperties(List.of());

        verify(mICarProperty, never()).injectVehicleProperties(any());
    }

    @Test
    public void testInjectVehicleProperties_remoteException() throws Exception {
        doThrow(RemoteException.class).when(mICarProperty).injectVehicleProperties(any());

        // Should not throw, only logs
        mCarPropertySimulationManager.injectVehicleProperties(List.of(mCarPropertyValue));

        verify(mICarProperty).injectVehicleProperties(any());
    }

    @Test
    public void testInjectVehicleProperties_permissionDenied() throws Exception {
        doThrow(SecurityException.class).when(mICarProperty).injectVehicleProperties(any());

        assertThrows(
                SecurityException.class,
                () ->
                        mCarPropertySimulationManager.injectVehicleProperties(
                                List.of(mCarPropertyValue)));
    }

    @Test
    public void testInjectVehicleProperties_injectionNotEnabled() throws Exception {
        doThrow(IllegalStateException.class).when(mICarProperty).injectVehicleProperties(any());

        assertThrows(
                IllegalStateException.class,
                () ->
                        mCarPropertySimulationManager.injectVehicleProperties(
                                List.of(mCarPropertyValue)));
    }

    @Test
    public void testInjectVehicleProperties_illegalArgument() throws Exception {
        doThrow(IllegalArgumentException.class).when(mICarProperty).injectVehicleProperties(any());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mCarPropertySimulationManager.injectVehicleProperties(
                                List.of(mCarPropertyValue)));
    }

    @Test
    public void testCreateCarPropertyValue() {
        assumeTrue(BuildHelper.isDebuggableBuild());

        when(mContextMock.checkCallingOrSelfPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES))
                .thenReturn(PERMISSION_GRANTED);

        CarPropertyValue<Integer> carPropertyValue =
                mCarPropertySimulationManager.createCarPropertyValue(
                        TEST_PROP_ID,
                        TEST_AREA_ID,
                        CarPropertyValue.STATUS_AVAILABLE,
                        TEST_TIMESTAMP,
                        TEST_VALUE);

        expectThat(carPropertyValue.getPropertyId()).isEqualTo(TEST_PROP_ID);
        expectThat(carPropertyValue.getAreaId()).isEqualTo(TEST_AREA_ID);
        expectThat(carPropertyValue.getStatus()).isEqualTo(CarPropertyValue.STATUS_AVAILABLE);
        expectThat(carPropertyValue.getTimestamp()).isEqualTo(TEST_TIMESTAMP);
        expectThat(carPropertyValue.getValue()).isEqualTo(TEST_VALUE);
    }

    @Test
    public void testCreateCarPropertyValue_nullValue() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mCarPropertySimulationManager.createCarPropertyValue(
                                TEST_PROP_ID,
                                TEST_AREA_ID,
                                CarPropertyValue.STATUS_AVAILABLE,
                                TEST_TIMESTAMP,
                                null));
    }

    @Test
    public void testCreateCarPropertyValue_permissionDenied() {
        when(mContextMock.checkCallingOrSelfPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES))
                .thenReturn(PERMISSION_DENIED);

        assertThrows(
                SecurityException.class,
                () ->
                        mCarPropertySimulationManager.createCarPropertyValue(
                                TEST_PROP_ID,
                                TEST_AREA_ID,
                                CarPropertyValue.STATUS_AVAILABLE,
                                TEST_TIMESTAMP,
                                TEST_VALUE));
    }

    @Test
    public void testCreateCarPropertyValue_nonDebuggableBuild() {
        assumeFalse(BuildHelper.isDebuggableBuild());

        when(mContextMock.checkCallingOrSelfPermission(Car.PERMISSION_INJECT_VEHICLE_PROPERTIES))
                .thenReturn(PERMISSION_GRANTED);

        assertThrows(
                IllegalStateException.class,
                () ->
                        mCarPropertySimulationManager.createCarPropertyValue(
                                TEST_PROP_ID,
                                TEST_AREA_ID,
                                CarPropertyValue.STATUS_AVAILABLE,
                                TEST_TIMESTAMP,
                                TEST_VALUE));
    }

    @Test
    public void testCarSubscriptionEventListenerToService_onEvent_DirectExecutor()
            throws Exception {
        when(mICarProperty.registerRecordingListener(mListenerCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(
                DIRECT_EXECUTOR, mCarRecorderListener);
        mListenerCaptor.getValue().onEvent(List.of(new CarPropertyEvent(0, mCarPropertyValue)));

        verify(mCarRecorderListener).onCarPropertyEvents(mCarPropertyValueCaptor.capture());
        assertThat(mCarPropertyValueCaptor.getValue()).containsExactly(mCarPropertyValue);
    }

    @Test
    public void testCarSubscriptionEventListenerToService_onEvent_HandlerExecutor()
            throws Exception {
        when(mICarProperty.registerRecordingListener(mListenerCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));
        doNothing().when(mCarRecorderListener).onCarPropertyEvents(anyList());

        mCarPropertySimulationManager.startRecordingVehicleProperties(null, mCarRecorderListener);
        mListenerCaptor.getValue().onEvent(List.of(new CarPropertyEvent(0, mCarPropertyValue)));

        verify(mMainHandlerSpy).post(mRunnableCaptor.capture());
        verify(mCarRecorderListener).onCarPropertyEvents(mCarPropertyValueCaptor.capture());
        assertThat(mCarPropertyValueCaptor.getValue()).containsExactly(mCarPropertyValue);
    }

    @Test
    public void testCarSubscriptionEventListenerToService_weakReferenceCleared() throws Exception {
        when(mICarProperty.registerRecordingListener(mListenerCaptor.capture()))
                .thenReturn(new CarPropertyConfigList(List.of(mCarPropertyConfig)));

        mCarPropertySimulationManager.startRecordingVehicleProperties(
                DIRECT_EXECUTOR, mCarRecorderListener);
        ICarPropertyEventListener listener = mListenerCaptor.getValue();
        mCarPropertySimulationManager = null; // Clear the strong reference
        System.gc(); // Hint to the JVM to run garbage collection

        // This should not cause a NullPointerException
        listener.onEvent(List.of(new CarPropertyEvent(0, mCarPropertyValue)));

        // The listener should not be called as the manager is garbage collected
        verify(mCarRecorderListener, never()).onCarPropertyEvents(any());
    }
}
