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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.car.internal.property.CarPropertyConfigList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public class CarPropertySimulationManagerUnitTest {

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
    @Mock
    private CarPropertyConfig mCarPropertyConfig;
    @Mock
    private Handler mHandler;
    @Mock
    private CarPropertyValue mCarPropertyValue;
    private CarPropertySimulationManager mCarPropertySimulationManager;
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Before
    public void setUp() throws Exception {
        when(mBinderMock.queryLocalInterface(anyString())).thenReturn(mICarProperty);
        when(mCar.getContext()).thenReturn(mContextMock);
        when(mCar.getEventHandler()).thenReturn(mHandler);
        mCarPropertySimulationManager = new CarPropertySimulationManager(mCar, mBinderMock);
    }

    @Test
    public void testStartRecordingVehicleProperties_validArguments() throws Exception {
        when(mICarProperty.registerRecordingListener(any())).thenReturn(new
                CarPropertyConfigList(List.of(mCarPropertyConfig)));

        assertWithMessage("Start recording vehicle properties with valid response")
                .that(mCarPropertySimulationManager.startRecordingVehicleProperties(DIRECT_EXECUTOR,
                        mCarRecorderListener)).containsExactly(mCarPropertyConfig);
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

        assertWithMessage("Original listener").that(mCarPropertySimulationManager
                .getCarRecorderListener()).isEqualTo(mCarRecorderListener);
        assertWithMessage("Original executor").that(mCarPropertySimulationManager
                .getCallbackExecutor()).isEqualTo(DIRECT_EXECUTOR);
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
        when(mCar.handleRemoteExceptionFromCarService(any(), eq(false))).thenReturn(false);

        assertWithMessage("call to isRecordingVehicleProeprties failed")
                .that(mCarPropertySimulationManager.isRecordingVehicleProperties()).isFalse();
    }

    @Test
    public void testStopRecordingVehicleProperties() throws Exception {
        doThrow(new RemoteException("stopRecordingVehicleProeprties failed")).when(mICarProperty)
                .stopRecordingVehicleProperties(any());

        mCarPropertySimulationManager.stopRecordingVehicleProperties();

        verify(mICarProperty).stopRecordingVehicleProperties(any());
    }

    @Test
    public void testEnableInjectionMode() throws Exception {
        mCarPropertySimulationManager.enableInjectionMode(List.of(123));

        verify(mICarProperty).enableInjectionMode(eq(new int[] {123}));
    }

    @Test
    public void testDisableInjectionMode() throws Exception {
        mCarPropertySimulationManager.disableInjectionMode();

        verify(mICarProperty).disableInjectionMode();
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
        when(mCar.handleRemoteExceptionFromCarService(any(), eq(false))).thenReturn(false);

        assertWithMessage("Calling isVehiclePropertyInjectionMode()").that(
                mCarPropertySimulationManager.isVehiclePropertyInjectionModeEnabled()).isFalse();
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
    public void testInjectVehicleProperties() throws Exception {
        List<CarPropertyValue> carPropertyValueList = List.of(mCarPropertyValue);
        mCarPropertySimulationManager.injectVehicleProperties(carPropertyValueList);

        verify(mICarProperty).injectVehicleProperties(eq(carPropertyValueList));
    }
}
