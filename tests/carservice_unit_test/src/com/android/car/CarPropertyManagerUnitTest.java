/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.car.VehiclePropertyIds.FUEL_DOOR_OPEN;
import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;
import static android.car.VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE;
import static android.car.VehiclePropertyIds.INFO_FUEL_DOOR_LOCATION;
import static android.car.VehiclePropertyIds.INVALID;
import static android.car.hardware.property.CarPropertyManager.SENSOR_RATE_ONCHANGE;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_ACCESS_DENIED;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_NOT_AVAILABLE;
import static android.car.hardware.property.VehicleHalStatusCode.STATUS_TRY_AGAIN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.GetPropertyServiceRequest;
import android.car.hardware.property.GetValueResult;
import android.car.hardware.property.ICarProperty;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.hardware.property.IGetAsyncPropertyResultCallback;
import android.car.hardware.property.PropertyAccessDeniedSecurityException;
import android.car.hardware.property.PropertyNotAvailableAndRetryException;
import android.car.hardware.property.PropertyNotAvailableException;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>This class contains unit tests for the {@link CarPropertyManager}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarPropertyManagerUnitTest {
    // Defined as vendor property.
    private static final int VENDOR_CONTINUOUS_PROPERTY = 0x21100111;
    private static final int VENDOR_ON_CHANGE_PROPERTY = 0x21100222;
    private static final int VENDOR_STATIC_PROPERTY = 0x21100333;

    private static final int BOOLEAN_PROP = FUEL_DOOR_OPEN;
    private static final int INT32_PROP = INFO_FUEL_DOOR_LOCATION;
    private static final int INT32_VEC_PROP = INFO_EV_CONNECTOR_TYPE;
    private static final int FLOAT_PROP = HVAC_TEMPERATURE_SET;

    private static final float MIN_UPDATE_RATE_HZ = 10;
    private static final float MAX_UPDATE_RATE_HZ = 100;
    private static final float FIRST_UPDATE_RATE_HZ = 50;
    private static final float LARGER_UPDATE_RATE_HZ = 50.1f;
    private static final float SMALLER_UPDATE_RATE_HZ = 49.9f;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Mock
    private Car mCar;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private ICarProperty mICarProperty;
    @Mock
    private Context mContext;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCarPropertyEventCallback;
    @Mock
    private CarPropertyManager.CarPropertyEventCallback mCarPropertyEventCallback2;
    @Mock
    private CarPropertyConfig mContinuousCarPropertyConfig;
    @Mock
    private CarPropertyConfig mOnChangeCarPropertyConfig;
    @Mock
    private CarPropertyConfig mStaticCarPropertyConfig;
    @Mock
    private CarPropertyManager.GetPropertyCallback mGetPropertyCallback;

    @Captor
    private ArgumentCaptor<Integer> mPropertyIdCaptor;
    @Captor
    private ArgumentCaptor<Float> mUpdateRateHzCaptor;
    private CarPropertyManager mCarPropertyManager;

    private static List<CarPropertyEvent> createErrorCarPropertyEventList() {
        CarPropertyValue<Integer> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0,
                CarPropertyValue.STATUS_ERROR, 0, -1);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_ERROR, value,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        return List.of(carPropertyEvent);
    }

    private static List<CarPropertyEvent> createCarPropertyEventList() {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
        CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, value);
        return List.of(carPropertyEvent);
    }

    @Before
    public void setUp() throws RemoteException {
        when(mCar.getContext()).thenReturn(mContext);
        when(mCar.getEventHandler()).thenReturn(mMainHandler);
        when(mCar.handleRemoteExceptionFromCarService(any(RemoteException.class), any()))
                .thenAnswer((inv) -> {
                    return inv.getArgument(1);
                });
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContinuousCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS);
        when(mContinuousCarPropertyConfig.getMinSampleRate()).thenReturn(MIN_UPDATE_RATE_HZ);
        when(mContinuousCarPropertyConfig.getMaxSampleRate()).thenReturn(MAX_UPDATE_RATE_HZ);
        when(mOnChangeCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE);
        when(mStaticCarPropertyConfig.getChangeMode()).thenReturn(
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC);
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_CONTINUOUS_PROPERTY})).thenReturn(
                ImmutableList.of(mContinuousCarPropertyConfig));
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_ON_CHANGE_PROPERTY})).thenReturn(
                ImmutableList.of(mOnChangeCarPropertyConfig));
        when(mICarProperty.getPropertyConfigList(new int[]{VENDOR_STATIC_PROPERTY})).thenReturn(
                ImmutableList.of(mStaticCarPropertyConfig));
        mCarPropertyManager = new CarPropertyManager(mCar, mICarProperty);
    }

    @Test
    public void testGetProperty_returnsValue() throws RemoteException {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isEqualTo(value);
    }

    @Test
    public void testGetProperty_unsupportedProperty() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getProperty(INVALID, 0));
    }

    private void setAppTargetSdk(int appTargetSdk) {
        mApplicationInfo.targetSdkVersion = appTargetSdk;
        mCarPropertyManager = new CarPropertyManager(mCar, mICarProperty);
    }

    @Test
    public void testGetProperty_notAvailableBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_NOT_AVAILABLE));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_notAvailableEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_NOT_AVAILABLE));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0)).isNull();
    }

    @Test
    public void testGetProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_accessDeniedBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_ACCESS_DENIED));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_accessDeniedEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_ACCESS_DENIED));

        assertThrows(PropertyAccessDeniedSecurityException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_internalErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_INTERNAL_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_internalErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_INTERNAL_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_unknownErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(-1));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetProperty_unknownErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(-1));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getProperty(HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetPropertyWithClass() throws Exception {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0).getValue())
                .isEqualTo(17.0f);
    }

    @Test
    public void testGetPropertyWithClass_mismatchClass() throws Exception {
        CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);

        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(value);

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getProperty(Integer.class, HVAC_TEMPERATURE_SET, 0));
    }

    @Test
    public void testGetPropertyWithClass_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0))
                .isNull();
    }

    @Test
    public void testGetPropertyWithClass_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getProperty(Float.class, HVAC_TEMPERATURE_SET, 0));
    }


    @Test
    public void testGetBooleanProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Boolean> value = new CarPropertyValue<>(BOOLEAN_PROP, 0, true);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0)).isTrue();
    }

    @Test
    public void testGetBooleanProperty_notAvailableBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_NOT_AVAILABLE));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_notAvailableEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_NOT_AVAILABLE));

        assertThrows(PropertyNotAvailableException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0)).isFalse();
    }

    @Test
    public void testGetBooleanProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_accessDeniedBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_ACCESS_DENIED));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_accessDeniedEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_ACCESS_DENIED));

        assertThrows(PropertyAccessDeniedSecurityException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_internalErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_INTERNAL_ERROR));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_internalErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_INTERNAL_ERROR));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_unknownErrorBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(-1));

        assertThrows(IllegalStateException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetBooleanProperty_unknownErrorEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(BOOLEAN_PROP, 0)).thenThrow(
                new ServiceSpecificException(-1));

        assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.getBooleanProperty(BOOLEAN_PROP, 0));
    }

    @Test
    public void testGetIntProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Integer> value = new CarPropertyValue<>(INT32_PROP, 0, 1);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getIntProperty(INT32_PROP, 0)).isEqualTo(1);
    }

    @Test
    public void testGetIntProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getIntProperty(INT32_PROP, 0)).isEqualTo(0);
    }

    @Test
    public void testGetIntProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(INT32_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getIntProperty(INT32_PROP, 0));
    }

    @Test
    public void testGetIntArrayProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Integer[]> value = new CarPropertyValue<>(INT32_VEC_PROP, 0,
                new Integer[]{1, 2});
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0)).isEqualTo(
                new int[]{1, 2});
    }

    @Test
    public void testGetIntArrayProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0)).isEqualTo(
                new int[0]);
    }

    @Test
    public void testGetIntArrayProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(INT32_VEC_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getIntArrayProperty(INT32_VEC_PROP, 0));
    }

    @Test
    public void testGetFloatProperty() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        CarPropertyValue<Float> value = new CarPropertyValue<>(FLOAT_PROP, 0, 1.f);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenReturn(value);

        assertThat(mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0)).isEqualTo(1.f);
    }

    @Test
    public void testGetFloatProperty_tryAgainBeforeR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.Q);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0)).isEqualTo(0.f);
    }

    @Test
    public void testGetFloatProperty_tryAgainEqualAfterR() throws Exception {
        setAppTargetSdk(Build.VERSION_CODES.R);
        when(mICarProperty.getProperty(FLOAT_PROP, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThrows(PropertyNotAvailableAndRetryException.class,
                () -> mCarPropertyManager.getFloatProperty(FLOAT_PROP, 0));
    }

    @Test
    public void testGetPropertiesAsync_propertySupported() throws RemoteException {
        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);

        ArgumentCaptor<List<GetPropertyServiceRequest>> argumentCaptor = ArgumentCaptor.forClass(
                List.class);
        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), anyLong());
        assertThat(argumentCaptor.getValue().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().get(0).getAreaId()).isEqualTo(0);
    }

    @Test
    public void testGetPropertiesAsyncWithTimeout() throws RemoteException {
        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()),
                /* timeoutInMs= */ 1000, null, null, mGetPropertyCallback);

        ArgumentCaptor<List<GetPropertyServiceRequest>> argumentCaptor = ArgumentCaptor.forClass(
                List.class);
        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), eq(1000L));
        assertThat(argumentCaptor.getValue().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().get(0).getAreaId()).isEqualTo(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetPropertiesAsync_illegalArgumentException() throws RemoteException {
        IllegalArgumentException exception = new IllegalArgumentException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(List.class),
                any(IGetAsyncPropertyResultCallback.class), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);
    }

    @Test(expected = SecurityException.class)
    public void testGetPropertiesAsync_SecurityException() throws RemoteException {
        SecurityException exception = new SecurityException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(List.class),
                any(IGetAsyncPropertyResultCallback.class), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);
    }

    @Test
    public void tsetGetPropertiesAsync_unsupportedProperty() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            mCarPropertyManager.getPropertiesAsync(List.of(
                    mCarPropertyManager.generateGetPropertyRequest(INVALID, 0)), null, null,
                    mGetPropertyCallback);
        });
    }

    @Test
    public void testGetPropertiesAsync_remoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).when(mICarProperty).getPropertiesAsync(any(List.class),
                any(IGetAsyncPropertyResultCallback.class), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mCar).handleRemoteExceptionFromCarService(any(RemoteException.class));
    }

    @Test
    public void testGetPropertiesAsync_clearRequestIdToClientInfo() throws RemoteException {
        CarPropertyManager.GetPropertyRequest getPropertyRequest = createPropertyRequest();
        IllegalArgumentException exception = new IllegalArgumentException();
        doThrow(exception).when(mICarProperty).getPropertiesAsync(any(List.class),
                any(IGetAsyncPropertyResultCallback.class), anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), null,
                        null, mGetPropertyCallback));

        clearInvocations(mICarProperty);
        doNothing().when(mICarProperty).getPropertiesAsync(any(List.class),
                any(IGetAsyncPropertyResultCallback.class), anyLong());
        ArgumentCaptor<List<GetPropertyServiceRequest>> argumentCaptor = ArgumentCaptor.forClass(
                List.class);

        mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), null, null,
                mGetPropertyCallback);

        verify(mICarProperty).getPropertiesAsync(argumentCaptor.capture(), any(), anyLong());
        assertThat(argumentCaptor.getValue().get(0).getRequestId()).isEqualTo(0);
        assertThat(argumentCaptor.getValue().get(0).getPropertyId())
                .isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(argumentCaptor.getValue().get(0).getAreaId()).isEqualTo(0);
    }

    @Test
    public void testGetPropertiesAsync_cancellationSignalCancelRequests() throws Exception {
        CarPropertyManager.GetPropertyRequest getPropertyRequest = createPropertyRequest();
        CancellationSignal cancellationSignal = new CancellationSignal();
        List<IGetAsyncPropertyResultCallback> callbackWrapper = new ArrayList<>();
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getPropertyServiceList = (List) args[0];
            GetPropertyServiceRequest getPropertyServiceRequest =
                    (GetPropertyServiceRequest) getPropertyServiceList.get(0);
            callbackWrapper.add((IGetAsyncPropertyResultCallback) args[1]);
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        mCarPropertyManager.getPropertiesAsync(List.of(getPropertyRequest), cancellationSignal,
                /* callbackExecutor= */ null, mGetPropertyCallback);

        // Cancel the pending request.
        cancellationSignal.cancel();

        verify(mICarProperty).cancelRequests(new int[]{0});

        // Call the manager callback after the request is already cancelled.
        GetValueResult getValueResult = new GetValueResult(0, null,
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
        assertThat(callbackWrapper.size()).isEqualTo(1);
        callbackWrapper.get(0).onGetValueResult(List.of(getValueResult));

        // No client callbacks should be called.
        verify(mGetPropertyCallback, never()).onFailure(any());
        verify(mGetPropertyCallback, never()).onSuccess(any());
    }

    private CarPropertyManager.GetPropertyRequest createPropertyRequest() {
        return mCarPropertyManager.generateGetPropertyRequest(HVAC_TEMPERATURE_SET, 0);
    }

    @Test
    public void testOnGetValueResult_onSuccess() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getPropertyServiceList = (List) args[0];
            GetPropertyServiceRequest getPropertyServiceRequest =
                    (GetPropertyServiceRequest) getPropertyServiceList.get(0);
            IGetAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IGetAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);

            CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
            GetValueResult getValueResult = new GetValueResult(0, value,
                    CarPropertyManager.STATUS_OK);

            getAsyncPropertyResultCallback.onGetValueResult(List.of(getValueResult));
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        ArgumentCaptor<CarPropertyManager.GetPropertyResult> value = ArgumentCaptor.forClass(
                CarPropertyManager.GetPropertyResult.class);

        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000)).onSuccess(value.capture());
        assertThat(value.getValue().getRequestId()).isEqualTo(0);
        assertThat(value.getValue().getCarPropertyValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testOnGetValueResult_onSuccessMultipleRequests() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List<GetPropertyServiceRequest> getPropertyServiceRequests =
                    (List<GetPropertyServiceRequest>) args[0];
            IGetAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IGetAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequests.size()).isEqualTo(2);
            assertThat(getPropertyServiceRequests.get(0).getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequests.get(0).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);
            assertThat(getPropertyServiceRequests.get(1).getRequestId()).isEqualTo(1);
            assertThat(getPropertyServiceRequests.get(1).getPropertyId()).isEqualTo(
                    HVAC_TEMPERATURE_SET);

            CarPropertyValue<Float> value = new CarPropertyValue<>(HVAC_TEMPERATURE_SET, 0, 17.0f);
            List<GetValueResult> getValueResults = new ArrayList<>();
            getValueResults.add(new GetValueResult(0, value, CarPropertyManager.STATUS_OK));
            getValueResults.add(new GetValueResult(1, value, CarPropertyManager.STATUS_OK));

            getAsyncPropertyResultCallback.onGetValueResult(getValueResults);
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        ArgumentCaptor<CarPropertyManager.GetPropertyResult> value = ArgumentCaptor.forClass(
                CarPropertyManager.GetPropertyResult.class);
        List<CarPropertyManager.GetPropertyRequest> getPropertyRequests = new ArrayList<>();
        getPropertyRequests.add(createPropertyRequest());
        getPropertyRequests.add(createPropertyRequest());

        mCarPropertyManager.getPropertiesAsync(getPropertyRequests, null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000).times(2)).onSuccess(value.capture());
        List<CarPropertyManager.GetPropertyResult> getPropertyResults = value.getAllValues();
        assertThat(getPropertyResults.get(0).getRequestId()).isEqualTo(0);
        assertThat(getPropertyResults.get(0).getCarPropertyValue().getValue()).isEqualTo(17.0f);
        assertThat(getPropertyResults.get(1).getRequestId()).isEqualTo(1);
    }

    @Test
    public void testOnGetValueResult_onFailure() throws RemoteException {
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            List getPropertyServiceList = (List) args[0];
            GetPropertyServiceRequest getPropertyServiceRequest =
                    (GetPropertyServiceRequest) getPropertyServiceList.get(0);
            IGetAsyncPropertyResultCallback getAsyncPropertyResultCallback =
                    (IGetAsyncPropertyResultCallback) args[1];

            assertThat(getPropertyServiceRequest.getRequestId()).isEqualTo(0);
            assertThat(getPropertyServiceRequest.getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);

            GetValueResult getValueResult = new GetValueResult(0, null,
                    CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);

            getAsyncPropertyResultCallback.onGetValueResult(List.of(getValueResult));
            return null;
        }).when(mICarProperty).getPropertiesAsync(any(), any(), anyLong());

        ArgumentCaptor<CarPropertyManager.GetPropertyError> value = ArgumentCaptor.forClass(
                CarPropertyManager.GetPropertyError.class);

        mCarPropertyManager.getPropertiesAsync(List.of(createPropertyRequest()), null, null,
                mGetPropertyCallback);

        verify(mGetPropertyCallback, timeout(1000)).onFailure(value.capture());
        assertThat(value.getValue().getRequestId()).isEqualTo(0);
        assertThat(value.getValue().getErrorCode()).isEqualTo(
                CarPropertyManager.STATUS_ERROR_INTERNAL_ERROR);
    }

    @Test
    public void testSetProperty_setsValue() throws RemoteException {
        mCarPropertyManager.setProperty(Float.class, HVAC_TEMPERATURE_SET, 0, 17.0f);

        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        verify(mICarProperty).setProperty(value.capture(), any());
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }

    @Test
    public void testSetProperty_unsupportedProperty() throws RemoteException {
        assertThrows(IllegalArgumentException.class, () -> {
            mCarPropertyManager.setProperty(Float.class, INVALID, 0, 17.0f);
        });
    }

    @Test
    public void testTegisterCallback_returnsFalseIfPropertyIdNotSupportedInVehicle()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VehiclePropertyIds.INVALID, FIRST_UPDATE_RATE_HZ)).isFalse();
        verify(mICarProperty, never()).registerListener(anyInt(), anyFloat(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersWithServiceOnFirstCallback() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(FIRST_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersWithMaxUpdateRateOnFirstCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, MAX_UPDATE_RATE_HZ + 1)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(MAX_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersWithMinUpdateRateOnFirstCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, MIN_UPDATE_RATE_HZ - 1)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(MIN_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersWithOnChangeRateForOnChangeProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersWithOnChangeRateForStaticProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_STATIC_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_STATIC_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_returnsFalseForRemoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).when(mICarProperty).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isFalse();
    }

    @Test
    public void testTegisterCallback_recoversAfterFirstRemoteException() throws RemoteException {
        RemoteException remoteException = new RemoteException();
        doThrow(remoteException).doNothing().when(mICarProperty).registerListener(
                eq(VENDOR_ON_CHANGE_PROPERTY), eq(CarPropertyManager.SENSOR_RATE_ONCHANGE),
                any(ICarPropertyEventListener.class));
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isFalse();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        verify(mICarProperty, times(2)).registerListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                eq(CarPropertyManager.SENSOR_RATE_ONCHANGE), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersTwiceWithHigherRateCallback() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, LARGER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                LARGER_UPDATE_RATE_HZ);
    }

    @Test
    public void testTegisterCallback_registersOnSecondLowerRateWithSameCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, SMALLER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                SMALLER_UPDATE_RATE_HZ);
    }

    @Test
    public void testTegisterCallback_doesNotRegistersOnSecondLowerRateCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, SMALLER_UPDATE_RATE_HZ)).isTrue();
        verify(mICarProperty).registerListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                eq(FIRST_UPDATE_RATE_HZ), any(ICarPropertyEventListener.class));
    }

    @Test
    public void testTegisterCallback_registersTwiceForDifferentProperties() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();
        verify(mICarProperty, times(2)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_ON_CHANGE_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
    }

    @Test
    public void testUnregisterCallback_doesNothingIfNothingRegistered() throws RemoteException {
        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback, VENDOR_STATIC_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_doesNothingIfPropertyIsNotRegisteredForCallback()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback, VENDOR_STATIC_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_doesNothingIfCallbackIsNotRegisteredForProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForSingleProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY);
        verify(mICarProperty).unregisterListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForSpecificProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY);
        verify(mICarProperty).unregisterListener(eq(VENDOR_ON_CHANGE_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersCallbackForBothProperties()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty, times(2)).unregisterListener(mPropertyIdCaptor.capture(),
                any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_ON_CHANGE_PROPERTY);
    }

    @Test
    public void testUnregisterCallback_unregistersAllCallbackForSingleProperty()
            throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
            VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
            VENDOR_ON_CHANGE_PROPERTY, CarPropertyManager.SENSOR_RATE_ONCHANGE)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty).unregisterListener(eq(VENDOR_CONTINUOUS_PROPERTY),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testUnregisterCallback_unregistersUpdatesRegisteredRateHz() throws RemoteException {
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback,
                VENDOR_CONTINUOUS_PROPERTY, FIRST_UPDATE_RATE_HZ)).isTrue();
        assertThat(mCarPropertyManager.registerCallback(mCarPropertyEventCallback2,
                VENDOR_CONTINUOUS_PROPERTY, LARGER_UPDATE_RATE_HZ)).isTrue();

        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback2);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
        verify(mICarProperty, times(3)).registerListener(mPropertyIdCaptor.capture(),
                mUpdateRateHzCaptor.capture(), any(ICarPropertyEventListener.class));
        assertThat(mPropertyIdCaptor.getAllValues()).containsExactly(VENDOR_CONTINUOUS_PROPERTY,
                VENDOR_CONTINUOUS_PROPERTY, VENDOR_CONTINUOUS_PROPERTY);
        assertThat(mUpdateRateHzCaptor.getAllValues()).containsExactly(FIRST_UPDATE_RATE_HZ,
                LARGER_UPDATE_RATE_HZ, FIRST_UPDATE_RATE_HZ);
    }

    @Test
    public void testUnregisterCallback_doesNothingWithPropertyIdIfNothingRegistered()
            throws RemoteException {
        mCarPropertyManager.unregisterCallback(mCarPropertyEventCallback);
        verify(mICarProperty, never()).unregisterListener(anyInt(),
                any(ICarPropertyEventListener.class));
    }

    @Test
    public void testOnErrorEvent_callbackIsCalledWithErrorEvent() throws RemoteException {
        List<CarPropertyEvent> eventList = createErrorCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(
                configs);
        ICarPropertyEventListener listener = getCarPropertyEventListener();

        listener.onEvent(eventList);

        // Wait until we get the on error event for the initial value.
        verify(mCarPropertyEventCallback, timeout(5000)).onErrorEvent(HVAC_TEMPERATURE_SET, 0,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
    }

    @Test
    public void testOnChangeEvent_callbackIsCalledWithEvent() throws RemoteException {
        List<CarPropertyEvent> eventList = createCarPropertyEventList();
        List<CarPropertyConfig> configs = List.of(
                CarPropertyConfig.newBuilder(Float.class, HVAC_TEMPERATURE_SET,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL).build());
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET})).thenReturn(
                configs);
        ICarPropertyEventListener listener = getCarPropertyEventListener();
        ArgumentCaptor<CarPropertyValue> value = ArgumentCaptor.forClass(CarPropertyValue.class);

        listener.onEvent(eventList);

        // Wait until we get the on property change event for the initial value.
        verify(mCarPropertyEventCallback, timeout(5000)).onChangeEvent(value.capture());
        assertThat(value.getValue().getPropertyId()).isEqualTo(HVAC_TEMPERATURE_SET);
        assertThat(value.getValue().getValue()).isEqualTo(17.0f);
    }

    private ICarPropertyEventListener getCarPropertyEventListener() throws RemoteException {
        ArgumentCaptor<ICarPropertyEventListener> carPropertyEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(ICarPropertyEventListener.class);
        mCarPropertyManager.registerCallback(mCarPropertyEventCallback, HVAC_TEMPERATURE_SET,
                SENSOR_RATE_ONCHANGE);

        verify(mICarProperty).registerListener(eq(HVAC_TEMPERATURE_SET), eq(SENSOR_RATE_ONCHANGE),
                carPropertyEventListenerArgumentCaptor.capture());

        return carPropertyEventListenerArgumentCaptor.getValue();
    }

    @Test
    public void testGetPropertyList() throws Exception {
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        when(mICarProperty.getPropertyList()).thenReturn(expectedConfigs);

        assertThat(mCarPropertyManager.getPropertyList()).isEqualTo(expectedConfigs);
    }

    @Test
    public void testGetPropertyList_withPropertyIds() throws Exception {
        Integer[] requestedPropertyIds = new Integer[] {
                VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        ArgumentCaptor<int[]> argumentCaptor = ArgumentCaptor.forClass(int[].class);
        when(mICarProperty.getPropertyConfigList(argumentCaptor.capture()))
                .thenReturn(expectedConfigs);

        assertThat(mCarPropertyManager.getPropertyList(new ArraySet<Integer>(requestedPropertyIds)))
                .isEqualTo(expectedConfigs);
        assertThat(argumentCaptor.getValue()).asList()
                .containsExactlyElementsIn(requestedPropertyIds);
    }

    @Test
    public void testGetPropertyList_filterUnsupportedPropertyIds() throws Exception {
        Integer[] requestedPropertyIds = new Integer[]{
                0, 1, VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        Integer[] filteredPropertyIds = new Integer[]{
                VENDOR_CONTINUOUS_PROPERTY, HVAC_TEMPERATURE_SET};
        List<CarPropertyConfig> expectedConfigs = mock(List.class);
        ArgumentCaptor<int[]> argumentCaptor = ArgumentCaptor.forClass(int[].class);
        when(mICarProperty.getPropertyConfigList(argumentCaptor.capture()))
                .thenReturn(expectedConfigs);

        assertThat(mCarPropertyManager.getPropertyList(new ArraySet<Integer>(requestedPropertyIds)))
                .isEqualTo(expectedConfigs);
        assertThat(argumentCaptor.getValue()).asList()
                .containsExactlyElementsIn(filteredPropertyIds);
    }

    @Test
    public void testGetCarPropertyConfig() throws Exception {
        CarPropertyConfig mockConfig = mock(CarPropertyConfig.class);
        List<CarPropertyConfig> expectedConfigs = List.of(mockConfig);
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET}))
                .thenReturn(expectedConfigs);

        assertThat(mCarPropertyManager.getCarPropertyConfig(HVAC_TEMPERATURE_SET))
                .isEqualTo(mockConfig);
    }

    @Test
    public void testGetCarPropertyConfig_noConfigReturned() throws Exception {
        when(mICarProperty.getPropertyConfigList(new int[]{HVAC_TEMPERATURE_SET}))
                .thenReturn(new ArrayList<CarPropertyConfig>());

        assertThat(mCarPropertyManager.getCarPropertyConfig(HVAC_TEMPERATURE_SET)).isNull();
    }

    @Test
    public void testGetCarPropertyConfig_unsupported() throws Exception {
        assertThat(mCarPropertyManager.getCarPropertyConfig(/* propId= */ 0)).isNull();
    }

    @Test
    public void testIsPropertyAvailable() throws Exception {
        CarPropertyValue<Integer> expectedValue = new CarPropertyValue<>(HVAC_TEMPERATURE_SET,
                /* areaId= */ 0, CarPropertyValue.STATUS_AVAILABLE, /* timestamp= */ 0,
                /* value= */ 1);
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenReturn(expectedValue);

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isTrue();
    }

    @Test
    public void testIsPropertyAvailable_notAvailable() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_NOT_AVAILABLE));

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }

    @Test
    public void testIsPropertyAvailable_tryAgain() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new ServiceSpecificException(STATUS_TRY_AGAIN));

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }


    @Test
    public void testIsPropertyAvailable_RemoteException() throws Exception {
        when(mICarProperty.getProperty(HVAC_TEMPERATURE_SET, 0)).thenThrow(
                new RemoteException());

        assertThat(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, /* areaId= */ 0))
                .isFalse();
    }

    @Test
    public void testIsPropertyAvailable_unsupported() throws Exception {
        assertThat(mCarPropertyManager.isPropertyAvailable(/* propId= */ 0, /* areaId= */ 0))
                .isFalse();
    }
}
