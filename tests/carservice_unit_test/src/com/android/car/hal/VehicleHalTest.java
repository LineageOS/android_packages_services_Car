/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.hal;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalMatchers.not;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.hardware.automotive.vehicle.V2_0.IVehicleCallback;
import android.hardware.automotive.vehicle.V2_0.SubscribeFlags;
import android.hardware.automotive.vehicle.V2_0.SubscribeOptions;
import android.hardware.automotive.vehicle.V2_0.VehicleAreaConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;

import com.android.car.CarServiceUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class VehicleHalTest {

    private static final int SOME_READ_ON_CHANGE_PROPERTY = 0x01;
    private static final int SOME_READ_WRITE_STATIC_PROPERTY = 0x02;
    private static final int SOME_BOOL_PROPERTY = VehiclePropertyType.BOOLEAN | 0x03;
    private static final int SOME_INT32_PROPERTY = VehiclePropertyType.INT32 | 0x04;
    private static final int SOME_INT32_VEC_PROPERTY = VehiclePropertyType.INT32_VEC | 0x05;
    private static final int SOME_FLOAT_PROPERTY = VehiclePropertyType.FLOAT | 0x06;
    private static final int SOME_FLOAT_VEC_PROPERTY = VehiclePropertyType.FLOAT_VEC | 0x07;
    private static final int UNSUPPORTED_PROPERTY = -1;

    private static final float ANY_SAMPLING_RATE = 60f;
    private static final int NO_FLAGS = 0;

    @Mock private IVehicle mIVehicle;

    @Mock private PowerHalService mPowerHalService;
    @Mock private PropertyHalService mPropertyHalService;
    @Mock private InputHalService mInputHalService;
    @Mock private VmsHalService mVmsHalService;
    @Mock private UserHalService mUserHalService;
    @Mock private DiagnosticHalService mDiagnosticHalService;
    @Mock private ClusterHalService mClusterHalService;
    @Mock private TimeHalService mTimeHalService;
    @Mock private HalClient mHalClient;

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            VehicleHal.class.getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    @Rule public final TestName mTestName = new TestName();

    private VehicleHal mVehicleHal;

    /** Hal services configurations */
    private final ArrayList<VehiclePropConfig> mConfigs = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        mVehicleHal = new VehicleHal(mPowerHalService,
                mPropertyHalService, mInputHalService, mVmsHalService, mUserHalService,
                mDiagnosticHalService, mClusterHalService, mTimeHalService, mHalClient,
                mHandlerThread);

        mConfigs.clear();

        String methodName = mTestName.getMethodName();
        if (!methodName.endsWith("_skipSetupInit")) {
            VehiclePropConfig powerHalConfig = new VehiclePropConfig();
            powerHalConfig.prop = SOME_READ_ON_CHANGE_PROPERTY;
            powerHalConfig.access = VehiclePropertyAccess.READ_WRITE;
            powerHalConfig.changeMode = VehiclePropertyChangeMode.ON_CHANGE;

            VehiclePropConfig propertyHalConfig = new VehiclePropConfig();
            propertyHalConfig.prop = SOME_READ_WRITE_STATIC_PROPERTY;
            propertyHalConfig.access = VehiclePropertyAccess.READ_WRITE;
            propertyHalConfig.changeMode = VehiclePropertyChangeMode.STATIC;

            init(powerHalConfig, propertyHalConfig);

            assertThat(VehicleHal.isPropertySubscribable(powerHalConfig)).isTrue();
            assertThat(VehicleHal.isPropertySubscribable(propertyHalConfig)).isFalse();
        }
    }

    private void init(VehiclePropConfig powerHalConfig, VehiclePropConfig propertyHalConfig)
            throws Exception {
        // Initialize PowerHAL service with a READ_WRITE and ON_CHANGE property
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_ON_CHANGE_PROPERTY});
        mConfigs.add(powerHalConfig);

        // Initialize PropertyHAL service with a READ_WRITE and STATIC property
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_WRITE_STATIC_PROPERTY});
        mConfigs.add(propertyHalConfig);

        // Initialize the remaining services with empty properties
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        when(mHalClient.getAllPropConfigs()).thenReturn(mConfigs);
        mVehicleHal.init();
    }

    private static Answer<Void> checkConfigs(ArrayList<VehiclePropConfig> configs) {
        return new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                assertThat(configs).isEqualTo(invocation.getArguments()[0]);
                return null;
            }
        };
    }

    @Test
    public void testInit_skipSetupInit() throws Exception {
        VehiclePropConfig powerHalConfig = new VehiclePropConfig();
        powerHalConfig.prop = SOME_READ_ON_CHANGE_PROPERTY;
        powerHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        powerHalConfig.changeMode = VehiclePropertyChangeMode.ON_CHANGE;

        VehiclePropConfig propertyHalConfig = new VehiclePropConfig();
        propertyHalConfig.prop = SOME_READ_WRITE_STATIC_PROPERTY;
        propertyHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        propertyHalConfig.changeMode = VehiclePropertyChangeMode.STATIC;

        // When takeProperties is called, verify the arguments. We cannot verify this afterwards
        // because the input arg is a reference that would be updated after the call. Mockito does
        // not do deep copy.
        doAnswer(checkConfigs(
                new ArrayList<VehiclePropConfig>(Arrays.asList(powerHalConfig))))
                .when(mPowerHalService).takeProperties(any());
        doAnswer(checkConfigs(
                new ArrayList<VehiclePropConfig>(Arrays.asList(propertyHalConfig))))
                .when(mPropertyHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mInputHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mVmsHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mUserHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mDiagnosticHalService).takeProperties(any());

        init(powerHalConfig, propertyHalConfig);

        verify(mPowerHalService).init();
        verify(mPropertyHalService).init();
        verify(mInputHalService).init();
        verify(mVmsHalService).init();
        verify(mUserHalService).init();
        verify(mDiagnosticHalService).init();
    }

    @Test
    public void testInitWith0SupportedProps_skipSetupInit() throws Exception {
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(new int[]{});
        when(mPowerHalService.isSupportedProperty(eq(SOME_READ_ON_CHANGE_PROPERTY)))
                .thenReturn(true);
        when(mPowerHalService.isSupportedProperty(not(eq(SOME_READ_ON_CHANGE_PROPERTY))))
                .thenReturn(false);
        VehiclePropConfig powerHalConfig = new VehiclePropConfig();
        powerHalConfig.prop = SOME_READ_ON_CHANGE_PROPERTY;
        powerHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        powerHalConfig.changeMode = VehiclePropertyChangeMode.ON_CHANGE;
        mConfigs.add(powerHalConfig);
        assertThat(VehicleHal.isPropertySubscribable(powerHalConfig)).isTrue();

        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(new int[]{});
        when(mPropertyHalService.isSupportedProperty(eq(SOME_READ_WRITE_STATIC_PROPERTY)))
                .thenReturn(true);
        when(mPropertyHalService.isSupportedProperty(not(eq(SOME_READ_WRITE_STATIC_PROPERTY))))
                .thenReturn(false);
        VehiclePropConfig propertyHalConfig = new VehiclePropConfig();
        propertyHalConfig.prop = SOME_READ_WRITE_STATIC_PROPERTY;
        propertyHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        propertyHalConfig.changeMode = VehiclePropertyChangeMode.STATIC;
        mConfigs.add(propertyHalConfig);
        assertThat(VehicleHal.isPropertySubscribable(propertyHalConfig)).isFalse();

        // Initialize the remaining services with empty properties
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        when(mHalClient.getAllPropConfigs()).thenReturn(mConfigs);

        mVehicleHal.init();
    }

    @Test
    public void testInitTwice_skipSetupInit() throws Exception {
        VehiclePropConfig powerHalConfig = new VehiclePropConfig();
        powerHalConfig.prop = SOME_READ_ON_CHANGE_PROPERTY;
        powerHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        powerHalConfig.changeMode = VehiclePropertyChangeMode.ON_CHANGE;

        VehiclePropConfig propertyHalConfig = new VehiclePropConfig();
        propertyHalConfig.prop = SOME_READ_WRITE_STATIC_PROPERTY;
        propertyHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        propertyHalConfig.changeMode = VehiclePropertyChangeMode.STATIC;

        init(powerHalConfig, propertyHalConfig);
        mVehicleHal.init();

        // getAllPropConfigs should only be called once.
        verify(mHalClient, times(1)).getAllPropConfigs();
    }

    @Test
    public void testInitWithEmptyConfigs_skipSetupInit() throws Exception {
        // Initialize PowerHAL service with a READ_WRITE and ON_CHANGE property
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_ON_CHANGE_PROPERTY});

        // Initialize PropertyHAL service with a READ_WRITE and STATIC property
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_WRITE_STATIC_PROPERTY});

        // Initialize the remaining services with empty properties
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        // Return empty prop configs.
        when(mHalClient.getAllPropConfigs()).thenReturn(new ArrayList<VehiclePropConfig>());

        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mPowerHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mPropertyHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mInputHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mVmsHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mUserHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mDiagnosticHalService).takeProperties(any());

        mVehicleHal.init();

        verify(mPowerHalService).init();
        verify(mPropertyHalService).init();
        verify(mInputHalService).init();
        verify(mVmsHalService).init();
        verify(mUserHalService).init();
        verify(mDiagnosticHalService).init();
    }

    @Test
    public void testInitWithNullConfigs_skipSetupInit() throws Exception {
        // Initialize PowerHAL service with a READ_WRITE and ON_CHANGE property
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_ON_CHANGE_PROPERTY});

        // Initialize PropertyHAL service with a READ_WRITE and STATIC property
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_WRITE_STATIC_PROPERTY});

        // Initialize the remaining services with empty properties
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        // Return empty prop configs.
        when(mHalClient.getAllPropConfigs()).thenReturn(null);

        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mPowerHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mPropertyHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mInputHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mVmsHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mUserHalService).takeProperties(any());
        doAnswer(checkConfigs(new ArrayList<VehiclePropConfig>()))
                .when(mDiagnosticHalService).takeProperties(any());

        mVehicleHal.init();

        verify(mPowerHalService).init();
        verify(mPropertyHalService).init();
        verify(mInputHalService).init();
        verify(mVmsHalService).init();
        verify(mUserHalService).init();
        verify(mDiagnosticHalService).init();
    }

    @Test
    public void testInitGetAllProdConfigsException_skipSetupInit() throws Exception {
        // Initialize PowerHAL service with a READ_WRITE and ON_CHANGE property
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_ON_CHANGE_PROPERTY});

        // Initialize PropertyHAL service with a READ_WRITE and STATIC property
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_WRITE_STATIC_PROPERTY});

        // Initialize the remaining services with empty properties
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        // Throw exception.
        when(mHalClient.getAllPropConfigs()).thenThrow(new RemoteException());

        assertThrows(RuntimeException.class, () -> mVehicleHal.init());
    }

    @Test
    public void testRelease() throws Exception {
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);

        mVehicleHal.release();

        verify(mPowerHalService).release();
        verify(mPropertyHalService).release();
        verify(mInputHalService).release();
        verify(mVmsHalService).release();
        verify(mUserHalService).release();
        verify(mDiagnosticHalService).release();
        verify(mHalClient).unsubscribe(SOME_READ_ON_CHANGE_PROPERTY);
    }

    @Test
    public void testReleaseUnsubscribeRemoteException() throws Exception {
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);
        // This exception should be captured into a warning.
        doThrow(new RemoteException()).when(mHalClient).unsubscribe(SOME_READ_ON_CHANGE_PROPERTY);

        mVehicleHal.release();

        verify(mPowerHalService).release();
        verify(mPropertyHalService).release();
        verify(mInputHalService).release();
        verify(mVmsHalService).release();
        verify(mUserHalService).release();
        verify(mDiagnosticHalService).release();
        verify(mHalClient).unsubscribe(SOME_READ_ON_CHANGE_PROPERTY);
    }

    @Test
    public void testGetters() {
        assertThat(mVehicleHal.getDiagnosticHal()).isEqualTo(mDiagnosticHalService);
        assertThat(mVehicleHal.getPowerHal()).isEqualTo(mPowerHalService);
        assertThat(mVehicleHal.getPropertyHal()).isEqualTo(mPropertyHalService);
        assertThat(mVehicleHal.getInputHal()).isEqualTo(mInputHalService);
        assertThat(mVehicleHal.getUserHal()).isEqualTo(mUserHalService);
        assertThat(mVehicleHal.getVmsHal()).isEqualTo(mVmsHalService);
        assertThat(mVehicleHal.getClusterHal()).isEqualTo(mClusterHalService);
        assertThat(mVehicleHal.getEvsHal()).isNotNull();
    }

    @Test
    public void testSubscribeProperty_registeringReadWriteAndOnChangeProperty() throws Exception {
        // Act
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);

        // Assert
        SubscribeOptions expectedOptions = new SubscribeOptions();
        expectedOptions.propId = SOME_READ_ON_CHANGE_PROPERTY;
        expectedOptions.sampleRate = ANY_SAMPLING_RATE;
        expectedOptions.flags = NO_FLAGS;

        verify(mHalClient).subscribe(eq(expectedOptions));
    }

    @Test
    public void testSubscribeProperty_defaultRateDefaultFlag() throws Exception {
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY);

        // Assert
        SubscribeOptions expectedOptions = new SubscribeOptions();
        expectedOptions.propId = SOME_READ_ON_CHANGE_PROPERTY;
        expectedOptions.sampleRate = 0f;
        expectedOptions.flags = SubscribeFlags.EVENTS_FROM_CAR;

        verify(mHalClient).subscribe(eq(expectedOptions));
    }

    @Test
    public void testSubscribeProperty_defaultFlag() throws Exception {
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE);

        // Assert
        SubscribeOptions expectedOptions = new SubscribeOptions();
        expectedOptions.propId = SOME_READ_ON_CHANGE_PROPERTY;
        expectedOptions.sampleRate = ANY_SAMPLING_RATE;
        expectedOptions.flags = SubscribeFlags.EVENTS_FROM_CAR;

        verify(mHalClient).subscribe(eq(expectedOptions));
    }

    @Test
    public void testSubScribeProperty_unownedProperty() throws Exception {
        // PropertyHalService does not own SOME_READ_ON_CHANGE_PROPERTY.
        assertThrows(IllegalArgumentException.class, () -> mVehicleHal.subscribeProperty(
                mPropertyHalService, SOME_READ_ON_CHANGE_PROPERTY, ANY_SAMPLING_RATE, NO_FLAGS));
    }

    @Test
    public void testSubScribeProperty_noConfigProperty() throws Exception {
        // Property UNSUPPORTED_PROPERTY does not have config.
        assertThrows(IllegalArgumentException.class, () -> mVehicleHal.subscribeProperty(
                mPowerHalService, UNSUPPORTED_PROPERTY, ANY_SAMPLING_RATE, NO_FLAGS));
    }

    @Test
    public void testSubscribeProperty_remoteException() throws Exception {
        doThrow(new RemoteException()).when(mHalClient).subscribe(any());

        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE);

        SubscribeOptions expectedOptions = new SubscribeOptions();
        expectedOptions.propId = SOME_READ_ON_CHANGE_PROPERTY;
        expectedOptions.sampleRate = ANY_SAMPLING_RATE;
        expectedOptions.flags = SubscribeFlags.EVENTS_FROM_CAR;

        // RemoteException is handled in subscribeProperty.
        verify(mHalClient).subscribe(eq(expectedOptions));
    }

    @Test
    public void testSubscribeProperty_registeringStaticProperty() throws Exception {
        // Act
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_WRITE_STATIC_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);

        // Assert
        verify(mHalClient, never()).subscribe(any(SubscribeOptions.class));
    }

    @Test
    public void testUnsubscribeProperty() throws Exception {
        // Arrange
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY);

        //Act
        mVehicleHal.unsubscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY);

        // Assert
        verify(mHalClient).unsubscribe(eq(SOME_READ_ON_CHANGE_PROPERTY));
    }

    @Test
    public void testUnsubscribeProperty_unsupportedProperty() throws Exception {
        //Act
        mVehicleHal.unsubscribeProperty(mPowerHalService, UNSUPPORTED_PROPERTY);

        // Assert
        verify(mHalClient, never()).unsubscribe(anyInt());
    }

    @Test
    public void testUnsubscribeProperty_unSubscribableProperty() throws Exception {
        //Act
        mVehicleHal.unsubscribeProperty(mPowerHalService, SOME_READ_WRITE_STATIC_PROPERTY);

        // Assert
        verify(mHalClient, never()).unsubscribe(anyInt());
    }

    @Test
    public void testGetSampleRate_unsupportedProperty() {
        assertThat(mVehicleHal.getSampleRate(UNSUPPORTED_PROPERTY)).isEqualTo(
                VehicleHal.NO_SAMPLE_RATE);
    }

    @Test
    public void testUnsubscribeProperty_remoteException() throws Exception {
        // Arrange
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY);
        doThrow(new RemoteException()).when(mHalClient).unsubscribe(anyInt());

        //Act
        mVehicleHal.unsubscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY);

        // Assert
        // RemoteException is handled in subscribeProperty.
        verify(mHalClient).unsubscribe(eq(SOME_READ_ON_CHANGE_PROPERTY));
    }

    @Test
    public void testGetSampleRate_supportedAndRegisteredProperty() {
        // Act
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);

        // Assert
        assertThat(mVehicleHal.getSampleRate(SOME_READ_ON_CHANGE_PROPERTY)).isEqualTo(
                ANY_SAMPLING_RATE);
    }

    @Test
    public void testOnPropertyEvent() {
        // Arrange
        List<VehiclePropValue> dispatchList = mock(List.class);
        when(mPowerHalService.getDispatchList()).thenReturn(dispatchList);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        ArrayList<VehiclePropValue> propValues = new ArrayList<>();
        propValues.add(propValue);

        // Act
        mVehicleHal.onPropertyEvent(propValues);

        // Assert
        verify(dispatchList).add(propValue);
        verify(mPowerHalService).onHalEvents(dispatchList);
        verify(dispatchList).clear();
    }

    @Test
    public void testOnPropertyEvent_existingInfo() {
        // Arrange
        List<VehiclePropValue> dispatchList = mock(List.class);
        when(mPowerHalService.getDispatchList()).thenReturn(dispatchList);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        ArrayList<VehiclePropValue> propValues = new ArrayList<>();
        propValues.add(propValue);

        // Act
        mVehicleHal.onPropertyEvent(propValues);
        mVehicleHal.onPropertyEvent(propValues);

        // Assert
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        mVehicleHal.dump(printWriter);
        String actual = writer.toString();

        // There should be 2 events.
        int index = actual.indexOf("Property:");
        assertThat(index).isNotEqualTo(-1);
        assertThat(actual.indexOf("Property:", index + 1)).isNotEqualTo(-1);
    }

    @Test
    public void testOnPropertyEvent_unsupportedProperty() {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = UNSUPPORTED_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        ArrayList<VehiclePropValue> propValues = new ArrayList<>();
        propValues.add(propValue);

        mVehicleHal.onPropertyEvent(propValues);

        verify(mPowerHalService, never()).onHalEvents(any());
    }

    @Test
    public void testOnPropertySetError() {
        // Arrange
        int errorCode = CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
        int propId = SOME_READ_ON_CHANGE_PROPERTY;
        int areaId = VehicleHal.NO_AREA;

        // Act
        mVehicleHal.onPropertySetError(errorCode, propId, areaId);

        // Assert
        verify(mPowerHalService).onPropertySetError(propId, areaId, errorCode);
    }

    @Test
    public void testOnPropertySetError_invalidProp() {
        // Arrange
        int errorCode = CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
        int propId = VehicleProperty.INVALID;
        int areaId = VehicleHal.NO_AREA;

        // Act
        mVehicleHal.onPropertySetError(errorCode, propId, areaId);

        // Assert
        verify(mPowerHalService, never()).onPropertySetError(propId, areaId, errorCode);
    }

    @Test
    public void testOnPropertySetError_unsupportedProp() {
        // Arrange
        int errorCode = CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
        int propId = UNSUPPORTED_PROPERTY;
        int areaId = VehicleHal.NO_AREA;

        // Act
        mVehicleHal.onPropertySetError(errorCode, propId, areaId);

        // Assert
        verify(mPowerHalService, never()).onPropertySetError(propId, areaId, errorCode);
    }

    @Test
    public void testInjectOnPropertySetError() {
        // Arrange
        int errorCode = CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_TRY_AGAIN;
        int propId = SOME_READ_ON_CHANGE_PROPERTY;
        int areaId = VehicleHal.NO_AREA;

        // Act
        mHandler.post(() -> mVehicleHal.onPropertySetError(errorCode, propId, areaId));
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        verify(mPowerHalService).onPropertySetError(propId, areaId, errorCode);
    }

    @Test
    public void testVehicleHalReconnected() throws Exception {
        // Arrange
        IVehicle mockVehicle = mock(IVehicle.class);

        // Act
        mVehicleHal.vehicleHalReconnected(mockVehicle);

        // Assert
        ArgumentCaptor<IVehicleCallback> vehicleCallbackCaptor = ArgumentCaptor.forClass(
                IVehicleCallback.class);
        ArgumentCaptor<ArrayList> optionsCaptor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mockVehicle, times(1)).subscribe(vehicleCallbackCaptor.capture(),
                optionsCaptor.capture());
        assertThat(vehicleCallbackCaptor.getValue()).isNotNull();
        assertThat(optionsCaptor.getValue()).isEmpty();
    }

    @Test
    public void testVehicleHalReconnectedSubscribedProperties() throws Exception {
        // Arrange
        IVehicle mockVehicle = mock(IVehicle.class);

        // Act
        mVehicleHal.subscribeProperty(mPowerHalService, SOME_READ_ON_CHANGE_PROPERTY,
                ANY_SAMPLING_RATE, NO_FLAGS);
        mVehicleHal.vehicleHalReconnected(mockVehicle);

        // Assert
        ArgumentCaptor<IVehicleCallback> vehicleCallbackCaptor = ArgumentCaptor.forClass(
                IVehicleCallback.class);
        ArgumentCaptor<ArrayList> optionsCaptor = ArgumentCaptor.forClass(ArrayList.class);
        verify(mockVehicle, times(1)).subscribe(vehicleCallbackCaptor.capture(),
                optionsCaptor.capture());
        assertThat(vehicleCallbackCaptor.getValue()).isNotNull();
        List<SubscribeOptions> options = optionsCaptor.getValue();
        assertThat(options.size()).isEqualTo(1);
        SubscribeOptions expectedOptions = new SubscribeOptions();
        expectedOptions.propId = SOME_READ_ON_CHANGE_PROPERTY;
        expectedOptions.sampleRate = ANY_SAMPLING_RATE;
        expectedOptions.flags = NO_FLAGS;
        assertThat(options.get(0)).isEqualTo(expectedOptions);
    }

    @Test
    public void testVehicleHalReconnectedSubscribeRemoteException() throws Exception {
        // Arrange
        IVehicle mockVehicle = mock(IVehicle.class);
        when(mockVehicle.subscribe(any(), any())).thenThrow(new RemoteException());

        // Act
        assertThrows(RuntimeException.class, () -> mVehicleHal.vehicleHalReconnected(mockVehicle));
    }

    @Test
    public void testGetIfAvailableOrFail() {
        // Arrange
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        // Act
        VehiclePropValue actual = mVehicleHal.getIfAvailableOrFail(SOME_READ_ON_CHANGE_PROPERTY,
                /* numberOfRetries= */ 1);

        // Assert
        assertThat(actual).isEqualTo(propValue);
    }

    @Test
    public void testGetIfAvailableOrFail_unsupportedProperty() {
        VehiclePropValue actual = mVehicleHal.getIfAvailableOrFail(UNSUPPORTED_PROPERTY,
                /* numberOfRetries= */ 1);

        assertThat(actual).isNull();
    }

    @Test
    public void testGetIfAvailableOrFail_serviceSpecificException() {
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenThrow(
                new ServiceSpecificException(0));

        assertThrows(IllegalStateException.class, () -> mVehicleHal.getIfAvailableOrFail(
                SOME_READ_ON_CHANGE_PROPERTY, /* numberOfRetries= */ 1));
    }

    @Test
    public void testGetIfAvailableOrFail_serviceSpecificExceptionRetrySucceed() {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenThrow(
                new ServiceSpecificException(0)).thenReturn(propValue);

        // Retry once.
        VehiclePropValue actual = mVehicleHal.getIfAvailableOrFail(SOME_READ_ON_CHANGE_PROPERTY,
                /* numberOfRetries= */ 2);

        assertThat(actual).isEqualTo(propValue);
        verify(mHalClient, times(2)).getValue(eq(propValue));
    }

    @Test
    public void testGetIfAvailableOrFailForEarlyStage_skipSetupInit() throws Exception {
        // Skip setup init() because this function would be called before init() is called.
        VehiclePropConfig powerHalConfig = new VehiclePropConfig();
        powerHalConfig.prop = SOME_READ_ON_CHANGE_PROPERTY;
        powerHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        powerHalConfig.changeMode = VehiclePropertyChangeMode.ON_CHANGE;

        VehiclePropConfig propertyHalConfig = new VehiclePropConfig();
        propertyHalConfig.prop = SOME_READ_WRITE_STATIC_PROPERTY;
        propertyHalConfig.access = VehiclePropertyAccess.READ_WRITE;
        propertyHalConfig.changeMode = VehiclePropertyChangeMode.STATIC;

        // Initialize PowerHAL service with a READ_WRITE and ON_CHANGE property
        when(mPowerHalService.getAllSupportedProperties()).thenReturn(
                new int[]{SOME_READ_ON_CHANGE_PROPERTY});
        mConfigs.add(powerHalConfig);

        // Initialize the remaining services with empty properties
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        when(mHalClient.getAllPropConfigs()).thenReturn(mConfigs);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        // Act
        VehiclePropValue actual = mVehicleHal.getIfAvailableOrFailForEarlyStage(
                SOME_READ_ON_CHANGE_PROPERTY, /* numberOfRetries= */ 1);

        // Assert
        assertThat(actual).isEqualTo(propValue);
    }

    @Test
    public void testGetClazz() throws Exception {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(1);
        propValue.value.int32Values.add(2);
        propValue.value.floatValues.add(1.1f);
        propValue.value.floatValues.add(1.2f);
        String testString = "test";
        propValue.value.stringValue = testString;
        ArrayList<Byte> testBytes = new ArrayList<Byte>(Arrays.asList((byte) 0x00, (byte) 0x01));
        propValue.value.bytes = testBytes;
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        assertThat(mVehicleHal.<Integer>get(Integer.class, propValue)).isEqualTo(1);
        assertThat(mVehicleHal.<Integer>get(int.class, propValue)).isEqualTo(1);
        assertThat(mVehicleHal.<Boolean>get(Boolean.class, propValue)).isTrue();
        assertThat(mVehicleHal.<Boolean>get(boolean.class, propValue)).isTrue();
        assertThat(mVehicleHal.<Float>get(Float.class, propValue)).isEqualTo(1.1f);
        assertThat(mVehicleHal.<Float>get(float.class, propValue)).isEqualTo(1.1f);
        assertThat(mVehicleHal.<Float[]>get(Float[].class, propValue)).isEqualTo(
                new Float[]{1.1f, 1.2f});
        assertThat(mVehicleHal.<Integer[]>get(Integer[].class, propValue)).isEqualTo(
                new Integer[]{1, 2});
        assertThat(mVehicleHal.<float[]>get(float[].class, propValue)).isEqualTo(
                new float[]{1.1f, 1.2f});
        assertThat(mVehicleHal.<int[]>get(int[].class, propValue)).isEqualTo(
                new int[]{1, 2});
        assertThat(mVehicleHal.<byte[]>get(byte[].class, propValue)).isEqualTo(
                new byte[]{(byte) 0x00, (byte) 0x01});
        assertThat(mVehicleHal.<String>get(String.class, propValue)).isEqualTo(testString);
    }

    @Test
    public void testGetClazz_unexpectedType() throws Exception {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        assertThrows(IllegalArgumentException.class, () -> mVehicleHal.<VehiclePropValue>get(
                VehiclePropValue.class, propValue));
    }

    @Test
    public void testGetClazz_defaultArea() throws Exception {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(1);
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        Integer actual = mVehicleHal.get(Integer.class, SOME_READ_ON_CHANGE_PROPERTY);

        assertThat(actual).isEqualTo(1);
        VehiclePropValue requestProp = new VehiclePropValue();
        requestProp.prop = SOME_READ_ON_CHANGE_PROPERTY;
        requestProp.areaId = VehicleHal.NO_AREA;
        verify(mHalClient).getValue(eq(requestProp));
    }

    // A test class to class protected method of VehicleHal.
    private class VehicleHalTestClass extends VehicleHal {
        VehicleHalTestClass(PowerHalService powerHal,
                PropertyHalService propertyHal,
                InputHalService inputHal,
                VmsHalService vmsHal,
                UserHalService userHal,
                DiagnosticHalService diagnosticHal,
                ClusterHalService clusterHalService,
                TimeHalService timeHalService,
                HalClient halClient,
                HandlerThread handlerThread) {
            super(powerHal, propertyHal, inputHal, vmsHal, userHal, diagnosticHal,
                    clusterHalService, timeHalService, halClient, handlerThread);
        }
    }

    @Test
    public void testSet() throws Exception {
        VehicleHalTestClass t = new VehicleHalTestClass(mPowerHalService,
                mPropertyHalService, mInputHalService, mVmsHalService, mUserHalService,
                mDiagnosticHalService, mClusterHalService, mTimeHalService,
                mHalClient, mHandlerThread);
        t.init();

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        t.set(propValue);

        verify(mHalClient).setValue(propValue);
    }

    @Test
    public void testSetter_bool() throws Exception {
        mVehicleHal.set(SOME_READ_ON_CHANGE_PROPERTY).to(true);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(1);
        verify(mHalClient).setValue(propValue);
    }

    @Test
    public void testSetter_int() throws Exception {
        mVehicleHal.set(SOME_READ_ON_CHANGE_PROPERTY).to(2);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(2);
        verify(mHalClient).setValue(propValue);
    }

    @Test
    public void testSetter_ints() throws Exception {
        mVehicleHal.set(SOME_READ_ON_CHANGE_PROPERTY).to(new int[]{1, 2});

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(1);
        propValue.value.int32Values.add(2);
        verify(mHalClient).setValue(propValue);
    }

    @Test
    public void testSetter_integers() throws Exception {
        mVehicleHal.set(SOME_READ_ON_CHANGE_PROPERTY).to(Arrays.asList(1, 2));

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = SOME_READ_ON_CHANGE_PROPERTY;
        propValue.areaId = VehicleHal.NO_AREA;
        propValue.value.int32Values.add(1);
        propValue.value.int32Values.add(2);
        verify(mHalClient).setValue(propValue);
    }

    // Testing dump methods

    @Test
    public void testDump() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        // Act
        mVehicleHal.dump(printWriter);

        // Assert
        String actual = writer.toString();
        assertThat(actual).contains("Property Id: 1 // 0x1 name: 0x1, service: mPowerHalService");
        assertThat(actual).contains(
                "Property Id: 2 // 0x2 name: 0x2, service: mPropertyHalService");
    }

    @Test
    public void testDumpListHals() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        // Act
        mVehicleHal.dumpListHals(printWriter);

        // Assert
        assertServiceNamesAreDumped(writer.toString());
    }

    @Test
    public void testDumpSpecificHals() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        // Act
        mVehicleHal.dumpSpecificHals(printWriter, "PowerHalService", "UnsupportedHalService");

        // Assert
        String actual = writer.toString();
        assertThat(actual).contains("No HAL named UnsupportedHalService");
        verify(mPowerHalService).dump(any());
    }

    private void assertServiceNamesAreDumped(String actual) {
        assertThat(actual).contains("com.android.car.hal.PowerHalService");
        assertThat(actual).contains("com.android.car.hal.InputHalService");
        assertThat(actual).contains("com.android.car.hal.DiagnosticHalService");
        assertThat(actual).contains("com.android.car.hal.VmsHalService");
        assertThat(actual).contains("com.android.car.hal.UserHalService");
        assertThat(actual).contains("com.android.car.hal.PropertyHalService");
    }

    @Test
    public void testDumpPropertyValueByCommand_all() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, /* propId= */ -1, /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("string: some_value");
    }

    @Test
    public void testDumpPropertyValueByCommand_allAreaIdsNoAreaConfig() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY,
                /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("string: some_value");

        VehiclePropValue requestProp = new VehiclePropValue();
        requestProp.prop = SOME_READ_ON_CHANGE_PROPERTY;
        requestProp.areaId = VehicleHal.NO_AREA;
        verify(mHalClient).getValue(requestProp);
    }

    @Test
    public void testDumpPropertyValueByCommand_allAreaIdsWithAreaConfig() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        VehicleAreaConfig areaConfig = new VehicleAreaConfig();
        areaConfig.areaId = 123;
        mConfigs.get(0).areaConfigs.add(areaConfig);

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY,
                /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("string: some_value");

        VehiclePropValue requestProp = new VehiclePropValue();
        requestProp.prop = SOME_READ_ON_CHANGE_PROPERTY;
        requestProp.areaId = 123;
        verify(mHalClient).getValue(requestProp);
    }

    @Test
    public void testDumpPropertyValueByCommand_propArea() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        VehicleAreaConfig areaConfig = new VehicleAreaConfig();
        areaConfig.areaId = 123;
        mConfigs.get(0).areaConfigs.add(areaConfig);

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY, 123);

        // Assert
        assertThat(writer.toString()).contains("string: some_value");

        VehiclePropValue requestProp = new VehiclePropValue();
        requestProp.prop = SOME_READ_ON_CHANGE_PROPERTY;
        requestProp.areaId = 123;
        verify(mHalClient).getValue(requestProp);
    }

    @Test
    public void testDumpPropertyValueByCommand_byConfigNoAreaConfigsGetValueException() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenThrow(
                new ServiceSpecificException(0));

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY,
                /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("Can not get property value");
    }

    @Test
    public void testDumpPropertyValueByCommand_byConfigWithAreaConfigsGetValueException() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenThrow(
                new ServiceSpecificException(0));

        VehicleAreaConfig areaConfig = new VehicleAreaConfig();
        areaConfig.areaId = 123;
        mConfigs.get(0).areaConfigs.add(areaConfig);

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY,
                /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("Can not get property value");
        VehiclePropValue requestProp = new VehiclePropValue();
        requestProp.prop = SOME_READ_ON_CHANGE_PROPERTY;
        requestProp.areaId = 123;
        verify(mHalClient).getValue(requestProp);
    }

    @Test
    public void testDumpPropertyValueByCommand_GetValueException() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenThrow(
                new ServiceSpecificException(0));

        // Act
        mVehicleHal.dumpPropertyValueByCommand(printWriter, SOME_READ_ON_CHANGE_PROPERTY,
                VehicleHal.NO_AREA);

        // Assert
        assertThat(writer.toString()).contains("Can not get property value");
    }

    @Test
    public void testDumpPropertyValueByCommand_unsupportedProp() {
        // Arrange
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        VehiclePropValue propValue = new VehiclePropValue();
        propValue.value = new VehiclePropValue.RawValue();
        propValue.value.stringValue = "some_value";
        when(mHalClient.getValue(any(VehiclePropValue.class))).thenReturn(propValue);

        // Act
        // Note here we cannot use UNSUPPORTED_PROPERTY because its value -1 has special meaning
        // in this function call.
        mVehicleHal.dumpPropertyValueByCommand(printWriter, 0, /* areaId= */-1);

        // Assert
        assertThat(writer.toString()).contains("not supported by HAL");
    }

    @Test
    public void testDumpPropertyConfigs() {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        mVehicleHal.dumpPropertyConfigs(printWriter, SOME_READ_ON_CHANGE_PROPERTY);

        assertThat(writer.toString()).contains("Property:0x1");
    }

    // Testing vehicle hal property getters

    @Test
    public void testGetForPropertyIdAndAreaId() {
        // Arrange
        int propertyId = 123;  // Any property id
        int areaId = 456;  // Any area id

        // Act
        mVehicleHal.get(propertyId, areaId);

        // Assert
        VehiclePropValue expectedPropValue = new VehiclePropValue();
        expectedPropValue.prop = propertyId;
        expectedPropValue.areaId = areaId;
        verify(mHalClient).getValue(eq(expectedPropValue));
    }

    @Test
    public void testGet_VehiclePropValue() {
        // Arrange
        VehiclePropValue propValue = new VehiclePropValue();

        // Act
        mVehicleHal.get(propValue);

        // Assert
        verify(mHalClient).getValue(propValue);
    }

    // Make a copy of the prop value reference so that we could check them later.
    // This is necessary because the passed in value list would be cleared after the call.
    private static Answer<Void> storePropValues(List<VehiclePropValue> values) {
        return new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                values.addAll((List<VehiclePropValue>) invocation.getArguments()[0]);
                return null;
            }
        };
    }

    private void setupInjectEventTest(int property, List<VehiclePropValue> values)
            throws Exception {
        VehiclePropConfig config = new VehiclePropConfig();
        config.prop = property;
        mConfigs.add(config);

        when(mPowerHalService.getAllSupportedProperties()).thenReturn(new int[]{property});
        when(mPropertyHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mInputHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mVmsHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mUserHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mDiagnosticHalService.getAllSupportedProperties()).thenReturn(new int[0]);
        when(mTimeHalService.getAllSupportedProperties()).thenReturn(new int[0]);

        when(mHalClient.getAllPropConfigs()).thenReturn(mConfigs);

        List<VehiclePropValue> dispatchList = new ArrayList<VehiclePropValue>();
        when(mPowerHalService.getDispatchList()).thenReturn(dispatchList);
        doAnswer(storePropValues(values)).when(mPowerHalService).onHalEvents(any());

        mVehicleHal.init();
    }

    @Test
    public void testInjectVhalEvent_intProperty_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_INT32_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_INT32_PROPERTY, VehicleHal.NO_AREA, "1", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        assertThat(values.size()).isEqualTo(1);
        VehiclePropValue prop = values.get(0);
        assertThat(prop.prop).isEqualTo(SOME_INT32_PROPERTY);
        assertThat(prop.areaId).isEqualTo(VehicleHal.NO_AREA);
        assertThat(prop.value.int32Values).isEqualTo(Arrays.asList(1));
        assertThat(prop.timestamp).isGreaterThan(time);
    }

    @Test
    public void testInjectVhalEvent_intVecProperty_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_INT32_VEC_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_INT32_VEC_PROPERTY, VehicleHal.NO_AREA, "1,2", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        assertThat(values.size()).isEqualTo(1);
        VehiclePropValue prop = values.get(0);
        assertThat(prop.prop).isEqualTo(SOME_INT32_VEC_PROPERTY);
        assertThat(prop.areaId).isEqualTo(VehicleHal.NO_AREA);
        assertThat(prop.value.int32Values).isEqualTo(Arrays.asList(1, 2));
        assertThat(prop.timestamp).isGreaterThan(time);
    }

    @Test
    public void testInjectVhalEvent_bool_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_BOOL_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_BOOL_PROPERTY, VehicleHal.NO_AREA, "True", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        assertThat(values.size()).isEqualTo(1);
        VehiclePropValue prop = values.get(0);
        assertThat(prop.prop).isEqualTo(SOME_BOOL_PROPERTY);
        assertThat(prop.areaId).isEqualTo(VehicleHal.NO_AREA);
        assertThat(prop.value.int32Values).isEqualTo(Arrays.asList(1));
        assertThat(prop.timestamp).isGreaterThan(time);
    }

    @Test
    public void testInjectVhalEvent_floatProperty_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_FLOAT_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_FLOAT_PROPERTY, VehicleHal.NO_AREA, "1.1", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        assertThat(values.size()).isEqualTo(1);
        VehiclePropValue prop = values.get(0);
        assertThat(prop.prop).isEqualTo(SOME_FLOAT_PROPERTY);
        assertThat(prop.areaId).isEqualTo(VehicleHal.NO_AREA);
        assertThat(prop.value.floatValues).isEqualTo(Arrays.asList(1.1f));
        assertThat(prop.timestamp).isGreaterThan(time);
    }

    @Test
    public void testInjectVhalEvent_floatVecProperty_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_FLOAT_VEC_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_FLOAT_VEC_PROPERTY, VehicleHal.NO_AREA, "1.1,1.2", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        assertThat(values.size()).isEqualTo(1);
        VehiclePropValue prop = values.get(0);
        assertThat(prop.prop).isEqualTo(SOME_FLOAT_VEC_PROPERTY);
        assertThat(prop.areaId).isEqualTo(VehicleHal.NO_AREA);
        assertThat(prop.value.floatValues).isEqualTo(Arrays.asList(1.1f, 1.2f));
        assertThat(prop.timestamp).isGreaterThan(time);
    }

    @Test
    public void testInjectVhalEvent_unsupportedProperty_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        // SOME_READ_ON_CHANGE_PROPERTY does not have a valid property type.
        setupInjectEventTest(SOME_READ_ON_CHANGE_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectVhalEvent(SOME_READ_ON_CHANGE_PROPERTY, VehicleHal.NO_AREA, "1", 0);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        verify(mPowerHalService, never()).onHalEvents(any());
    }

    @Test
    public void testInjectContinuousVhalEvent_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_INT32_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectContinuousVhalEvent(SOME_INT32_PROPERTY, VehicleHal.NO_AREA, "1", 10, 1);
        // Wait for injection to complete.
        SystemClock.sleep(1000);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert

        // Should be called multiple times, might be less than 10 times, so make it 5 to be safe.
        assertThat(values.size()).isGreaterThan(5);
    }

    @Test
    public void testInjectContinuousVhalEvent_unsupportedProp_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        // SOME_READ_ON_CHANGE_PROPERTY does not have a valid property type.
        setupInjectEventTest(SOME_READ_ON_CHANGE_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectContinuousVhalEvent(
                SOME_READ_ON_CHANGE_PROPERTY, VehicleHal.NO_AREA, "1", 10, 1);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        verify(mPowerHalService, never()).onHalEvents(any());
    }

    @Test
    public void testInjectContinuousVhalEvent_invalidSampleRate_skipSetupInit() throws Exception {
        // Arrange
        List<VehiclePropValue> values = new ArrayList<VehiclePropValue>();
        setupInjectEventTest(SOME_INT32_PROPERTY, values);
        long time = SystemClock.elapsedRealtimeNanos();

        // Act
        mVehicleHal.injectContinuousVhalEvent(SOME_INT32_PROPERTY, VehicleHal.NO_AREA, "1", -1, 1);
        CarServiceUtils.runOnLooperSync(mHandlerThread.getLooper(), () -> {});

        // Assert
        verify(mPowerHalService, never()).onHalEvents(any());
    }
}
