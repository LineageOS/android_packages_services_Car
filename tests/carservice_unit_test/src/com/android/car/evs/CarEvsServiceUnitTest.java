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

package com.android.car.evs;

import static android.car.evs.CarEvsManager.ERROR_NONE;
import static android.car.evs.CarEvsManager.ERROR_UNAVAILABLE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_ACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_INACTIVE;
import static android.car.evs.CarEvsManager.SERVICE_STATE_REQUESTED;
import static android.car.evs.CarEvsManager.SERVICE_STATE_UNAVAILABLE;
import static android.car.evs.CarEvsManager.STREAM_EVENT_STREAM_STOPPED;
import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_ERROR;
import static android.car.hardware.property.CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE;

import static com.android.car.CarLog.TAG_EVS;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.car.evs.CarEvsManager.CarEvsStreamEvent;
import android.car.evs.CarEvsStatus;
import android.car.evs.ICarEvsStatusListener;
import android.car.evs.ICarEvsStreamCallback;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.JavaMockitoHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.HardwareBuffer;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Display;
import android.util.Log;

import com.android.car.BuiltinPackageDependency;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceUtils;
import com.android.car.hal.EvsHalService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Semaphore;

/**
 * <p>This class contains unit tests for the {@link CarEvsService}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarEvsServiceUnitTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarEvsServiceUnitTest.class.getSimpleName();

    private static final String COMMAND_TO_USE_DEFAULT_CAMERA = "default";
    private static final String CAMERA_TO_USE = "/dev/video10";
    private static final String DEFAULT_CAMERA_ID = "/dev/default";
    private static final int INVALID_ARG = -1;
    private static final int SERVICE_STATE_UNKNOWN = -1;
    private static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";
    private static final CarPropertyValue<Integer> GEAR_SELECTION_PROPERTY_NEUTRAL =
        new CarPropertyValue<>(VehicleProperty.GEAR_SELECTION, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_NEUTRAL);
    private static final CarPropertyValue<Integer> GEAR_SELECTION_PROPERTY_REVERSE =
        new CarPropertyValue<>(VehicleProperty.GEAR_SELECTION, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_REVERSE);
    private static final CarPropertyValue<Integer> CURRENT_GEAR_PROPERTY_REVERSE =
        new CarPropertyValue<>(VehicleProperty.CURRENT_GEAR, VehicleArea.GLOBAL,
                               VehicleGear.GEAR_REVERSE);
    private static final String VALID_EVS_CAMERA_ACTIVITY_COMPONENT_NAME =
            "com.android.car.evs/com.android.car.evs.MockActivity";
    // ComponentName.unflattenFromString() returns a null object on below String object because it
    // does not contain an expected separator, '/'.
    private static final String INVALID_EVS_CAMERA_ACTIVITY_COMPONENT_NAME = "InvalidComponentName";
    // Minimum delay to receive callbacks.
    private static final int DEFAULT_TIMEOUT_IN_MS = 100;

    @Mock private CarPropertyService mMockCarPropertyService;
    @Mock private ClassLoader mMockClassLoader;
    @Mock private Context mMockContext;
    @Mock private Context mMockBuiltinPackageContext;
    @Mock private DisplayManager mMockDisplayManager;
    @Mock private EvsHalService mMockEvsHalService;
    @Mock private EvsHalWrapperImpl mMockEvsHalWrapper;
    @Mock private PackageManager mMockPackageManager;
    @Mock private Resources mMockResources;
    @Mock private Display mMockDisplay;

    @Captor private ArgumentCaptor<DisplayManager.DisplayListener> mDisplayListenerCaptor;
    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;
    @Captor private ArgumentCaptor<ICarPropertyEventListener> mGearSelectionListenerCaptor;
    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<StateMachine.HalCallback> mHalCallbackCaptor;

    private CarEvsService mCarEvsService;
    private Random mRandom = new Random();

    public CarEvsServiceUnitTest() {
        super(TAG_EVS);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ServiceManager.class)
            .spyStatic(Binder.class)
            .spyStatic(CarServiceUtils.class)
            .spyStatic(CarEvsService.class)
            .spyStatic(StateMachine.class)
            .spyStatic(BuiltinPackageDependency.class);
    }

    @Rule
    public TestName mTestName = new TestName();

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageName()).thenReturn(SYSTEMUI_PACKAGE_NAME);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getString(com.android.car.R.string.config_evsRearviewCameraId))
                .thenReturn(DEFAULT_CAMERA_ID);
        when(mMockContext.getSystemService(DisplayManager.class)).thenReturn(mMockDisplayManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        doNothing().when(mMockContext).startActivity(mIntentCaptor.capture());

        if (mTestName.getMethodName().endsWith("InvalidEvsCameraActivity")) {
            when(mMockResources.getString(com.android.car.R.string.config_evsCameraActivity))
                    .thenReturn(INVALID_EVS_CAMERA_ACTIVITY_COMPONENT_NAME);
        } else {
            when(mMockResources.getString(com.android.car.R.string.config_evsCameraActivity))
                    .thenReturn(VALID_EVS_CAMERA_ACTIVITY_COMPONENT_NAME);
        }
        when(mMockResources.getString(
                com.android.internal.R.string.config_systemUIServiceComponent))
                .thenReturn("com.android.systemui/com.android.systemui.SystemUIService");

        when(mMockBuiltinPackageContext.getClassLoader()).thenReturn(mMockClassLoader);
        doReturn(EvsHalWrapperImpl.class).when(mMockClassLoader)
                .loadClass(BuiltinPackageDependency.EVS_HAL_WRAPPER_CLASS);

        mockEvsHalService();
        mockEvsHalWrapper();
        doReturn(mMockEvsHalWrapper).when(
                () -> StateMachine.createHalWrapper(any(), mHalCallbackCaptor.capture()));

        // Get the property listener
        when(mMockCarPropertyService
                .registerListenerSafe(anyInt(), anyFloat(),
                        mGearSelectionListenerCaptor.capture())).thenReturn(true);

        mCarEvsService = new CarEvsService(mMockContext, mMockBuiltinPackageContext,
                        mMockEvsHalService, mMockCarPropertyService);
        mCarEvsService.init();
    }

    @After
    public void tearDown() throws Exception {
        mCarEvsService = null;
    }

    @Test
    public void testIsSupported() throws Exception {
        assertThat(mCarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_REARVIEW)).isTrue();
        assertThat(mCarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW)).isFalse();
        assertThrows(IllegalArgumentException.class, () -> mCarEvsService.isSupported(INVALID_ARG));
    }

    @Test
    public void testSessionTokenGeneration() throws Exception {
        // This test case is disguised as the SystemUI package.
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(UserHandle.USER_SYSTEM);
        assertThat(mCarEvsService.generateSessionToken()).isNotNull();

        int uid = Binder.getCallingUid();
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(uid);
        assertThat(mCarEvsService.generateSessionToken()).isNotNull();
    }

    @Test
    public void testGetCurrentStatus() {
        CarEvsStatus status = mCarEvsService.getCurrentStatus();
        assertThat(status.getServiceType()).isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(status.getState()).isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testRegisterAndUnregisterStatusListener() {
        EvsStatusListenerImpl testListener = new EvsStatusListenerImpl();
        mCarEvsService.registerStatusListener(testListener);
        mCarEvsService.unregisterStatusListener(testListener);
    }

    @Test
    public void testServiceInit() {
        when(mMockEvsHalWrapper.init()).thenReturn(false);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(2)).init();

        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(3)).init();

        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt())).thenReturn(null);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(4)).init();

        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_NEUTRAL);
        mCarEvsService.init();
        verify(mMockEvsHalWrapper, times(5)).init();
    }

    @Test
    public void testServiceRelease() {
        mCarEvsService.release();
        verify(mMockEvsHalWrapper).release();

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.release();
        verify(mMockCarPropertyService).unregisterListenerSafe(anyInt(), any());
    }

    @Test
    public void testOnEvent() {
        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);

        // Request a REARVIEW via HAL Event. CarEvsService should enter REQUESTED state.
        mCarEvsService.mEvsTriggerListener.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW,
                /* on= */ true);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isTrue();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);

        // Request a video stream with a given token. CarEvsService should enter ACTIVE state and
        // gives a callback upon the frame event.
        Bundle extras = mIntentCaptor.getValue().getExtras();
        assertThat(extras).isNotNull();
        IBinder token = extras.getBinder(CarEvsManager.EXTRA_SESSION_TOKEN);
        assertThat(mCarEvsService.startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                  token, spiedCallback)).isEqualTo(ERROR_NONE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isTrue();

        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);
        assertThat(spiedCallback.waitForFrames(/* expected= */ 1)).isTrue();

        // Request stopping a current activity. CarEvsService should give us a callback with
        // STREAM_STOPPED event and ehter INACTIVE state.
        mCarEvsService.mEvsTriggerListener.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW,
                /* on= */ false);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_INACTIVE)).isTrue();
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED)).isTrue();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);


        // Request an unsupported service. CarEvsService should decline a request and stay at the
        // same state.
        mCarEvsService.mEvsTriggerListener.onEvent(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW,
                /* on= */ true);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isFalse();
    }

    @Test
    public void testInvalidStreamCallback() {
        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();
        mCarEvsService.setStreamCallback(null);
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);

        SystemClock.sleep(DEFAULT_TIMEOUT_IN_MS);
        verify(mMockEvsHalWrapper).doneWithFrame(anyInt());

        // Nothing to verify from below line but added to increase the code coverage.
        mHalCallbackCaptor.getValue().onHalEvent(/* eventType= */ 0);
    }

    @Test
    public void testHalDeath() {
        mHalCallbackCaptor.getValue().onHalDeath();
        verify(mMockEvsHalWrapper, times(2)).connectToHalServiceIfNecessary();
    }

    @Test
    public void testTransitionFromUnavailableToInactive() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());

        mCarEvsService.registerStatusListener(spiedStatusListener);
        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.stopActivity();
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_INACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);
        verify(mMockEvsHalWrapper).connectToHalServiceIfNecessary();

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.setStreamCallback(null);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        mCarEvsService.mEvsTriggerListener.onEvent(CarEvsManager.SERVICE_TYPE_REARVIEW,
                /* on= */ false);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isFalse();
        verify(mMockEvsHalWrapper).connectToHalServiceIfNecessary();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.stopVideoStream(spiedCallback);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_INACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);
        verify(spiedCallback, times(3)).asBinder();

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        when(spiedCallback.asBinder()).thenReturn(null);
        mCarEvsService.setStreamCallback(spiedCallback);
        mCarEvsService.stopVideoStream(spiedCallback);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_INACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);
        verify(spiedCallback, times(6)).asBinder();

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        mCarEvsService.stopActivity();
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_INACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);
    }

    @Test
    public void testTransitionFromInactiveToInactive() {
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testTransitionFromActiveToInactive() {
        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(null);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_ACTIVE);
    }

    @Test
    public void testTransitionFromUnknownToInactive() {
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testTransitionFromUnavailableToActive() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);
    }

    @Test
    public void testTransitionFromInactiveToActive() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);

        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isTrue();
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_ACTIVE));

        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(false);
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);

        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(false);
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);

        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(true);
        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(true);
    }

    @Test
    public void testTransitionFromRequestedToActive() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);

        mCarEvsService.setServiceState(SERVICE_STATE_REQUESTED);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_ACTIVE);
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_ACTIVE));

        mCarEvsService.setServiceState(SERVICE_STATE_REQUESTED);
        assertThrows(NullPointerException.class,
                () -> mCarEvsService.startVideoStream(
                        CarEvsManager.SERVICE_TYPE_REARVIEW, null, null));

        mCarEvsService.setServiceState(SERVICE_STATE_REQUESTED);
        when(mMockEvsHalWrapper.openCamera(anyString())).thenReturn(false);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testTransitionFromActiveToActive() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());

        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(streamCallback);
        mCarEvsService.registerStatusListener(spiedStatusListener);
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, streamCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isFalse();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_ACTIVE);

        // Transition from unknown states
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isFalse();
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testTransitionFromUnavailableToRequested() {
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);

        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_UNAVAILABLE);

        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(true);
        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);

        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isTrue();
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_REQUESTED));
    }

    @Test
    public void testTransitionFromInactiveToRequested() {
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedStatusListener);
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);

        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_REQUESTED));
    }

    @Test
    public void testTransitionFromActiveToRequested() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.setStreamCallback(streamCallback);
        mCarEvsService.registerStatusListener(spiedStatusListener);

        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);

        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_REQUESTED));
    }

    @Test
    public void testTransitionFromUnknownToRequested() {
        mCarEvsService.setServiceState(SERVICE_STATE_UNKNOWN);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW));
        assertWithMessage("Verify current status of CarEvsService")
                .that(thrown).hasMessageThat()
                .contains("CarEvsService is in the unknown state.");
    }

    @Test
    public void testCarPropertyEvent() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                                                  GEAR_SELECTION_PROPERTY_REVERSE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testCarPropertyEventWithInvalidEvsCameraActivity() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                                                  GEAR_SELECTION_PROPERTY_REVERSE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testDuplicatedDisplayEvent() throws Exception {
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockDisplayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(mMockDisplay);
        when(mMockDisplay.getState()).thenReturn(Display.STATE_ON);

        // Set a last HAL event to require a camera activity and give onDisplayChanged() callback
        // twice.
        mCarEvsService.init();
        mCarEvsService.setLastEvsHalEvent(/* timestamp= */ 0, CarEvsManager.SERVICE_TYPE_REARVIEW,
                                          /* on= */ true);
        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.registerStatusListener(spiedStatusListener);

        verify(mMockDisplayManager).registerDisplayListener(
                    mDisplayListenerCaptor.capture(), any(Handler.class));

        mDisplayListenerCaptor.getValue().onDisplayChanged(Display.DEFAULT_DISPLAY);
        mDisplayListenerCaptor.getValue().onDisplayChanged(Display.DEFAULT_DISPLAY);

        // Confirm that we have received onStatusChanged() callback only once.
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isTrue();
        verify(spiedStatusListener).onStatusChanged(argThat(
                received -> received.getState() == SERVICE_STATE_REQUESTED));
        verify(mMockContext).startActivity(any());

        // Also, verify that we have received an intent for a camera activity.
        assertThat(mIntentCaptor.getValue().getComponent()).isEqualTo(
                ComponentName.unflattenFromString(VALID_EVS_CAMERA_ACTIVITY_COMPONENT_NAME));

        Bundle extras = mIntentCaptor.getValue().getExtras();
        assertThat(extras).isNotNull();
        assertThat(extras.getBinder(CarEvsManager.EXTRA_SESSION_TOKEN)).isNotNull();
    }

    @Test
    public void testEmptyCarPropertyEvent() throws Exception {
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList());

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testNonPropertyChangeEvent() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_ERROR,
                                                  GEAR_SELECTION_PROPERTY_REVERSE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testNonGearSelectionProperty() throws Exception {
        CarPropertyEvent event = new CarPropertyEvent(PROPERTY_EVENT_PROPERTY_CHANGE,
                                                      CURRENT_GEAR_PROPERTY_REVERSE);
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        when(mMockEvsHalService.isEvsServiceRequestSupported()).thenReturn(false);
        when(mMockCarPropertyService.getPropertySafe(anyInt(), anyInt()))
                .thenReturn(GEAR_SELECTION_PROPERTY_REVERSE);

        mCarEvsService.setToUseGearSelection(/* useGearSelection= */ true);
        mCarEvsService.init();
        mGearSelectionListenerCaptor.getValue().onEvent(Arrays.asList(event));

        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testStartActivity() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());
        int bufferId = mRandom.nextInt();
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        mCarEvsService.registerStatusListener(spiedStatusListener);

        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isTrue();

        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedCallback))
                .isEqualTo(ERROR_NONE);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_ACTIVE)).isTrue();

        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);
        assertThat(spiedCallback.waitForFrames(/* expected= */ 1)).isTrue();
        verify(spiedCallback)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
    }

    @Test
    public void testStartActivityFromUnavailableState() {
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(true);
        mCarEvsService.setServiceState(SERVICE_STATE_UNAVAILABLE);

        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        verify(mMockEvsHalWrapper, times(2)).connectToHalServiceIfNecessary();
    }

    @Test
    public void testStartActivityFromInactiveState() {
        mCarEvsService.setServiceState(SERVICE_STATE_INACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartActivityFromActiveState() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        mCarEvsService.setStreamCallback(streamCallback);

        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartActivityFromRequestedState() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        mCarEvsService.setStreamCallback(streamCallback);

        mCarEvsService.setServiceState(SERVICE_STATE_REQUESTED);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testRequestDifferentServiceInRequestedState() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        EvsStatusListenerImpl spiedStatusListener = spy(new EvsStatusListenerImpl());

        mCarEvsService.setStreamCallback(streamCallback);
        mCarEvsService.registerStatusListener(spiedStatusListener);
        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(spiedStatusListener.waitFor(SERVICE_STATE_REQUESTED)).isTrue();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);

        assertThat(mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW))
                .isEqualTo(ERROR_UNAVAILABLE);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStartAndStopActivity() {
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();

        mCarEvsService.setStreamCallback(streamCallback);
        mCarEvsService.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
        assertThat(mCarEvsService.getCurrentStatus().getServiceType())
                .isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);

        mCarEvsService.stopActivity();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testStopVideoStream() {
        EvsStreamCallbackImpl streamCallback0 = new EvsStreamCallbackImpl();
        EvsStreamCallbackImpl streamCallback1 = new EvsStreamCallbackImpl();
        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(null);
        mCarEvsService.stopVideoStream(streamCallback1);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_ACTIVE);

        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(streamCallback0);
        mCarEvsService.stopVideoStream(streamCallback1);
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_ACTIVE);
    }

    @Test
    public void testRequestToStartVideoTwice() {
        EvsStreamCallbackImpl spiedStreamCallback0 = spy(new EvsStreamCallbackImpl());
        EvsStreamCallbackImpl spiedStreamCallback1 = spy(new EvsStreamCallbackImpl());

        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();

        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedStreamCallback0))
                .isEqualTo(ERROR_NONE);
        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);

        int anotherBufferId = mRandom.nextInt();
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, null, spiedStreamCallback1))
                .isEqualTo(ERROR_NONE);
        mHalCallbackCaptor.getValue().onFrameEvent(anotherBufferId, buffer);
        assertThat(spiedStreamCallback0.waitForFrames(/* expected= */ 1)).isTrue();
        assertThat(spiedStreamCallback1.waitForFrames(/* expected= */ 1)).isTrue();
        verify(spiedStreamCallback0)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
        verify(spiedStreamCallback1)
                .onNewFrame(argThat(received -> received.getId() == anotherBufferId));
    }

    @Test
    public void testStartAndStopVideoStreamWithoutSessionToken() {
        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);
        assertThat(spiedCallback.waitForFrames(/* expected= */ 1)).isTrue();
        verify(spiedCallback)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
        mCarEvsService.stopVideoStream(spiedCallback);
    }

    @Test
    public void testStartAndStopVideoStreamWithSessionToken() throws Exception {
        // This test case is disguised as the SystemUI package.
        int uid = Binder.getCallingUid();
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(uid);

        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();

        // Create a buffer to circulate
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, token, spiedCallback))
                .isEqualTo(ERROR_NONE);

        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);
        assertThat(spiedCallback.waitForFrames(/* expected= */ 1)).isTrue();
        mCarEvsService.stopVideoStream(spiedCallback);
        verify(spiedCallback)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
    }

    @Test
    public void testRequestSurroundView() {
        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW, token, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
    }

    @Test
    public void testRequestRearviewButNoHal() {
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(false);
        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();
        EvsStreamCallbackImpl streamCallback = new EvsStreamCallbackImpl();
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, token, streamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);
    }

    @Test
    public void testCommandToSetRearviewCameraId() {
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(DEFAULT_CAMERA_ID);
        assertThat(mCarEvsService.setRearviewCameraIdFromCommand(CAMERA_TO_USE)).isTrue();
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(CAMERA_TO_USE);
        assertThat(mCarEvsService.setRearviewCameraIdFromCommand(COMMAND_TO_USE_DEFAULT_CAMERA))
                .isTrue();
        assertThat(mCarEvsService.getRearviewCameraIdFromCommand()).isEqualTo(DEFAULT_CAMERA_ID);
    }

    @Test
    public void testHalEvents() {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        mCarEvsService.setStreamCallback(spiedCallback);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_NONE);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_NONE)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_NONE);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_STREAM_STARTED);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_STREAM_STARTED)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STARTED);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_STREAM_STOPPED);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_FRAME_DROPPED);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_FRAME_DROPPED)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_FRAME_DROPPED);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_TIMEOUT);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_TIMEOUT)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_TIMEOUT);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_PARAMETER_CHANGED);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_PARAMETER_CHANGED))
                .isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_PARAMETER_CHANGED);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_PRIMARY_OWNER_CHANGED);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_PRIMARY_OWNER_CHANGED))
                .isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_PRIMARY_OWNER_CHANGED);

        mHalCallbackCaptor.getValue().onHalEvent(CarEvsManager.STREAM_EVENT_OTHER_ERRORS);
        assertThat(spiedCallback.waitForEvent(CarEvsManager.STREAM_EVENT_OTHER_ERRORS)).isTrue();
        verify(spiedCallback).onStreamEvent(CarEvsManager.STREAM_EVENT_OTHER_ERRORS);
    }

    @Test
    public void testHandleStreamCallbackCrash() throws Exception {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        verify(spiedCallback, atLeastOnce()).asBinder();
        verify(spiedCallback, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testHandleStreamCallbackCrashAndRequestActivity() throws Exception {
        EvsStreamCallbackImpl spiedCallback = spy(new EvsStreamCallbackImpl());
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                  /* token= */ null, spiedCallback))
                .isEqualTo(ERROR_NONE);

        verify(spiedCallback, atLeastOnce()).asBinder();
        verify(spiedCallback, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setLastEvsHalEvent(/* timestamp= */ 0, CarEvsManager.SERVICE_TYPE_REARVIEW,
                                          /* on= */ true);
        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_REQUESTED);
    }

    @Test
    public void testStatusListenerCrash() throws Exception {
        EvsStatusListenerImpl spiedListener = spy(new EvsStatusListenerImpl());
        mCarEvsService.registerStatusListener(spiedListener);

        verify(spiedListener, atLeastOnce()).asBinder();
        verify(spiedListener, atLeastOnce()).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        IBinder.DeathRecipient binderDeathRecipient = mDeathRecipientCaptor.getValue();
        assertWithMessage("CarEvsService binder death recipient")
            .that(binderDeathRecipient).isNotNull();

        binderDeathRecipient.binderDied();
        assertThat(mCarEvsService.getCurrentStatus().getState())
                .isEqualTo(SERVICE_STATE_INACTIVE);
        mCarEvsService.unregisterStatusListener(spiedListener);
    }

    @Test
    public void testPriorityStreamClient() throws Exception {
        // This test case is disguised as the SystemUI package.
        int uid = Binder.getCallingUid();
        when(mMockPackageManager.getPackageUidAsUser(SYSTEMUI_PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(uid);
        IBinder token = mCarEvsService.generateSessionToken();
        assertThat(token).isNotNull();
        HardwareBuffer buffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        int bufferId = mRandom.nextInt();
        EvsStreamCallbackImpl spiedStreamCallback0 = spy(new EvsStreamCallbackImpl());
        EvsStreamCallbackImpl spiedStreamCallback1 = spy(new EvsStreamCallbackImpl());

        // Configures the service to be in its ACTIVE state and use a spied stream callback.
        mCarEvsService.setServiceState(SERVICE_STATE_ACTIVE);
        mCarEvsService.setStreamCallback(spiedStreamCallback0);

        // Requests starting the rearview video stream.
        assertThat(mCarEvsService
                .startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW, token, spiedStreamCallback1))
                .isEqualTo(ERROR_NONE);
        mHalCallbackCaptor.getValue().onFrameEvent(bufferId, buffer);
        verify(spiedStreamCallback0, timeout(3000)).onStreamEvent(STREAM_EVENT_STREAM_STOPPED);
        verify(spiedStreamCallback1)
                .onNewFrame(argThat(received -> received.getId() == bufferId));
    }

    private void mockEvsHalService() throws Exception {
        when(mMockEvsHalService.isEvsServiceRequestSupported())
                .thenReturn(true);
    }

    private void mockEvsHalWrapper() throws Exception {
        when(mMockEvsHalWrapper.init()).thenReturn(true);
        when(mMockEvsHalWrapper.isConnected()).thenReturn(true);
        when(mMockEvsHalWrapper.openCamera(any())).thenReturn(true);
        when(mMockEvsHalWrapper.connectToHalServiceIfNecessary()).thenReturn(true);
        when(mMockEvsHalWrapper.requestToStartVideoStream()).thenReturn(true);
    }

    /**
     * Class that implements the listener interface and gets called back from
     * {@link android.car.evs.CarEvsManager.CarEvsStatusListener}.
     */
    private final static class EvsStatusListenerImpl extends ICarEvsStatusListener.Stub {
        private final Semaphore mSemaphore = new Semaphore(0);

        private CarEvsStatus mLastUpdate;

        @Override
        public void onStatusChanged(CarEvsStatus status) {
            Log.i(TAG, "Received a status change notification: " + status.getState());
            mLastUpdate = status;
            mSemaphore.release();
        }

        public boolean waitFor(int expected) {
            return waitFor(expected, DEFAULT_TIMEOUT_IN_MS);
        }

        public boolean waitFor(int expected, int timeout) {
            try {
                while (true) {
                    JavaMockitoHelper.await(mSemaphore, timeout);
                    if(expected == mLastUpdate.getState()) {
                        return true;
                    }
                }
            } catch (IllegalStateException | InterruptedException e) {
                Log.d(TAG, "Failure to wait for status update, " + e);
                return false;
            }
        }
    }

    /**
     * Class that implements the listener interface and gets called back from
     * {@link android.hardware.automotive.evs.IEvsCameraStream}.
     */
    private final static class EvsStreamCallbackImpl extends ICarEvsStreamCallback.Stub {
        private final Semaphore mFrameSemaphore = new Semaphore(0);
        private final Semaphore mEventSemaphore = new Semaphore(0);

        private int mLastEvent = CarEvsManager.STREAM_EVENT_NONE;

        @Override
        public void onStreamEvent(@CarEvsStreamEvent int event) {
            Log.i(TAG, "Received stream event " + event);
            mLastEvent = event;
            mEventSemaphore.release();
        }

        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            // Return a buffer immediately
            Log.i(TAG, "Received buffer " + buffer.getId());
            mFrameSemaphore.release();
        }

        public boolean waitForEvent(int expected) {
            return waitForEvent(expected, DEFAULT_TIMEOUT_IN_MS);
        }

        public boolean waitForEvent(int expected, int timeout) {
            try {
                while (true) {
                    JavaMockitoHelper.await(mEventSemaphore, timeout);
                    if (expected == mLastEvent) {
                        return true;
                    }
                }
            } catch (IllegalStateException | InterruptedException e) {
                Log.d(TAG, "Failure to wait for an event " + expected);
                return false;
            }
        }

        public boolean waitForFrames(int expected) {
            return waitForFrames(expected, DEFAULT_TIMEOUT_IN_MS);
        }

        public boolean waitForFrames(int expected, int timeout) {
            try {
                while (expected > 0) {
                    JavaMockitoHelper.await(mFrameSemaphore, timeout);
                    expected -= 1;
                }
            } catch (IllegalStateException | InterruptedException e) {
                Log.d(TAG, "Failure to wait for " + expected + " frames.");
                return false;
            }

            return true;
        }
    }
}
