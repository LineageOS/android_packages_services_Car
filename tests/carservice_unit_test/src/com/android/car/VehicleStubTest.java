/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.automotive.vehicle.IVehicle;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(MockitoJUnitRunner.class)
public class VehicleStubTest {

    private static final int TEST_PROP = 1;
    private static final int TEST_ACCESS = 2;
    private static final float TEST_SAMPLE_RATE = 3.0f;
    private static final int TEST_VALUE = 3;
    private static final int TEST_AREA = 4;
    private static final int TEST_STATUS = 5;

    @Mock
    private IVehicle mAidlVehicle;
    @Mock
    private IBinder mAidlBinder;
    @Mock
    private android.hardware.automotive.vehicle.V2_0.IVehicle mHidlVehicle;

    private VehicleStub mAidlVehicleStub;
    private VehicleStub mHidlVehicleStub;

    @Before
    public void setUp() {
        when(mAidlVehicle.asBinder()).thenReturn(mAidlBinder);

        mAidlVehicleStub = new AidlVehicleStub(mAidlVehicle);
        mHidlVehicleStub = new HidlVehicleStub(mHidlVehicle);

        assertThat(mAidlVehicleStub.isValid()).isTrue();
        assertThat(mHidlVehicleStub.isValid()).isTrue();
    }

    @Test
    public void testGetInterfaceDescriptorHidl() throws Exception {
        mHidlVehicleStub.getInterfaceDescriptor();

        verify(mHidlVehicle).interfaceDescriptor();
    }

    @Test
    public void testGetInterfaceDescriptorAidl() throws Exception {
        mAidlVehicleStub.getInterfaceDescriptor();

        verify(mAidlBinder).getInterfaceDescriptor();
    }

    @Test(expected = IllegalStateException.class)
    public void testGetInterfaceDescriptorRemoteException() throws Exception {
        when(mAidlBinder.getInterfaceDescriptor()).thenThrow(new RemoteException());

        mAidlVehicleStub.getInterfaceDescriptor();
    }

    @Test
    public void testLinkToDeathHidl() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);

        mHidlVehicleStub.linkToDeath(recipient);

        verify(mHidlVehicle).linkToDeath(recipient, 0);
    }

    @Test
    public void testLinkToDeathAidl() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);

        mAidlVehicleStub.linkToDeath(recipient);

        verify(mAidlBinder).linkToDeath(recipient, 0);
    }

    @Test(expected = IllegalStateException.class)
    public void testLinkToDeathRemoteException() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);
        doThrow(new RemoteException()).when(mAidlBinder).linkToDeath(recipient, 0);

        mAidlVehicleStub.linkToDeath(recipient);
    }

    @Test
    public void testUnlinkToDeathHidl() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);

        mHidlVehicleStub.unlinkToDeath(recipient);

        verify(mHidlVehicle).unlinkToDeath(recipient);
    }

    @Test
    public void testUnlinkToDeathAidl() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);

        mAidlVehicleStub.unlinkToDeath(recipient);

        verify(mAidlBinder).unlinkToDeath(recipient, 0);
    }

    @Test
    public void testUnlinkToDeathRemoteException() throws Exception {
        IVehicleDeathRecipient recipient = mock(IVehicleDeathRecipient.class);
        doThrow(new RemoteException()).when(mHidlVehicle).unlinkToDeath(recipient);

        mHidlVehicleStub.unlinkToDeath(recipient);
    }

    @Test
    public void testGetAllPropConfigsHidl() throws Exception {
        ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropConfig> hidlConfigs = new
                ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropConfig>();
        android.hardware.automotive.vehicle.V2_0.VehiclePropConfig hidlConfig =
                new android.hardware.automotive.vehicle.V2_0.VehiclePropConfig();
        hidlConfig.prop = TEST_PROP;
        hidlConfig.access = TEST_ACCESS;
        hidlConfigs.add(hidlConfig);

        when(mHidlVehicle.getAllPropConfigs()).thenReturn(hidlConfigs);

        HalPropConfig[] configs = mHidlVehicleStub.getAllPropConfigs();

        assertThat(configs.length).isEqualTo(1);
        assertThat(configs[0].getPropId()).isEqualTo(TEST_PROP);
        assertThat(configs[0].getAccess()).isEqualTo(TEST_ACCESS);
    }

    @Test
    public void testSubscribeHidl() throws Exception {
        SubscribeOptions aidlOptions = new SubscribeOptions();
        aidlOptions.propId = TEST_PROP;
        aidlOptions.sampleRate = TEST_SAMPLE_RATE;
        android.hardware.automotive.vehicle.V2_0.SubscribeOptions hidlOptions =
                new android.hardware.automotive.vehicle.V2_0.SubscribeOptions();
        hidlOptions.propId = TEST_PROP;
        hidlOptions.sampleRate = TEST_SAMPLE_RATE;
        hidlOptions.flags = android.hardware.automotive.vehicle.V2_0.SubscribeFlags.EVENTS_FROM_CAR;

        HalClientCallback callback = mock(HalClientCallback.class);
        VehicleStub.VehicleStubCallback stubCallback = mHidlVehicleStub.newCallback(callback);

        mHidlVehicleStub.subscribe(stubCallback, new SubscribeOptions[]{aidlOptions});

        verify(mHidlVehicle).subscribe(
                stubCallback.getHidlCallback(),
                new ArrayList<android.hardware.automotive.vehicle.V2_0.SubscribeOptions>(
                        Arrays.asList(hidlOptions)));
    }

    @Test
    public void testUnsubscribeHidl() throws Exception {
        HalClientCallback callback = mock(HalClientCallback.class);
        VehicleStub.VehicleStubCallback stubCallback = mHidlVehicleStub.newCallback(callback);

        mHidlVehicleStub.unsubscribe(stubCallback, TEST_PROP);

        verify(mHidlVehicle).unsubscribe(stubCallback.getHidlCallback(), TEST_PROP);
    }

    @Test
    public void testGetHidl() throws Exception {
        doAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue =
                        (android.hardware.automotive.vehicle.V2_0.VehiclePropValue) args[0];
                assertThat(propValue.prop).isEqualTo(TEST_PROP);
                android.hardware.automotive.vehicle.V2_0.IVehicle.getCallback callback =
                        (android.hardware.automotive.vehicle.V2_0.IVehicle.getCallback) args[1];
                callback.onValues(StatusCode.OK, propValue);
                return null;
            }
        }).when(mHidlVehicle).get(any(), any());

        HalPropValueBuilder builder = new HalPropValueBuilder(/*isAidl=*/false);
        HalPropValue value = builder.build(TEST_PROP, 0, TEST_VALUE);

        HalPropValue gotValue = mHidlVehicleStub.get(value);

        assertThat(gotValue).isEqualTo(value);
    }

    @Test(expected = ServiceSpecificException.class)
    public void testGetHidlError() throws Exception {
        doAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue =
                        (android.hardware.automotive.vehicle.V2_0.VehiclePropValue) args[0];
                assertThat(propValue.prop).isEqualTo(TEST_PROP);
                android.hardware.automotive.vehicle.V2_0.IVehicle.getCallback callback =
                        (android.hardware.automotive.vehicle.V2_0.IVehicle.getCallback) args[1];
                callback.onValues(StatusCode.INVALID_ARG, propValue);
                return null;
            }
        }).when(mHidlVehicle).get(any(), any());

        HalPropValueBuilder builder = new HalPropValueBuilder(/*isAidl=*/false);
        HalPropValue value = builder.build(TEST_PROP, 0, TEST_VALUE);

        mHidlVehicleStub.get(value);
    }

    @Test
    public void testSetHidl() throws Exception {
        HalPropValueBuilder builder = new HalPropValueBuilder(/*isAidl=*/false);
        HalPropValue value = builder.build(TEST_PROP, 0, TEST_VALUE);
        android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue =
                new android.hardware.automotive.vehicle.V2_0.VehiclePropValue();
        propValue.prop = TEST_PROP;
        propValue.value.int32Values.add(TEST_VALUE);

        when(mHidlVehicle.set(propValue)).thenReturn(StatusCode.OK);

        mHidlVehicleStub.set(value);
    }

    @Test
    public void testSetHidlError() throws Exception {
        HalPropValueBuilder builder = new HalPropValueBuilder(/*isAidl=*/false);
        HalPropValue value = builder.build(TEST_PROP, 0, TEST_VALUE);
        android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue =
                new android.hardware.automotive.vehicle.V2_0.VehiclePropValue();
        propValue.prop = TEST_PROP;
        propValue.value.int32Values.add(TEST_VALUE);

        when(mHidlVehicle.set(propValue)).thenReturn(StatusCode.INVALID_ARG);

        ServiceSpecificException exception = assertThrows(ServiceSpecificException.class, () -> {
            mHidlVehicleStub.set(value);
        });
        assertThat(exception.errorCode).isEqualTo(StatusCode.INVALID_ARG);
    }

    @Test
    public void testHidlVehicleCallbackOnPropertyEvent() throws Exception {
        HalClientCallback callback = mock(HalClientCallback.class);
        android.hardware.automotive.vehicle.V2_0.IVehicleCallback.Stub hidlCallback =
                mHidlVehicleStub.newCallback(callback).getHidlCallback();
        android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue =
                new android.hardware.automotive.vehicle.V2_0.VehiclePropValue();
        propValue.prop = TEST_PROP;
        propValue.value.int32Values.add(TEST_VALUE);
        HalPropValueBuilder builder = new HalPropValueBuilder(/*isAidl=*/false);
        HalPropValue halPropValue = builder.build(TEST_PROP, 0, TEST_VALUE);

        hidlCallback.onPropertyEvent(
                new ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropValue>(
                        Arrays.asList(propValue)));

        verify(callback).onPropertyEvent(new ArrayList<HalPropValue>(Arrays.asList(halPropValue)));
    }

    @Test
    public void testHidlVehicleCallbackOnPropertySetError() throws Exception {
        HalClientCallback callback = mock(HalClientCallback.class);
        android.hardware.automotive.vehicle.V2_0.IVehicleCallback.Stub hidlCallback =
                mHidlVehicleStub.newCallback(callback).getHidlCallback();
        VehiclePropError error = new VehiclePropError();
        error.propId = TEST_PROP;
        error.areaId = TEST_AREA;
        error.errorCode = TEST_STATUS;

        hidlCallback.onPropertySetError(TEST_STATUS, TEST_PROP, TEST_AREA);

        verify(callback).onPropertySetError(new ArrayList<VehiclePropError>(Arrays.asList(error)));
    }
}
