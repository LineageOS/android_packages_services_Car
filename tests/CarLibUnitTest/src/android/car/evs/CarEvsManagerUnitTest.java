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

package android.car.evs;

import static android.car.evs.CarEvsManager.ERROR_NONE;
import static android.car.evs.CarEvsManager.ERROR_UNAVAILABLE;
import static android.car.feature.Flags.FLAG_CAR_EVS_STREAM_MANAGEMENT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.evs.CarEvsManager.CarEvsStatusListener;
import android.car.evs.CarEvsManager.CarEvsStreamCallback;
import android.car.feature.FakeFeatureFlagsImpl;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.car.internal.ICarBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * <p>This class contains unit tests for the {@link CarEvsManager}.
 */
@RunWith(MockitoJUnitRunner.class)
public final class CarEvsManagerUnitTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().build();

    private static final int[] SERVICE_TYPES = {
        CarEvsManager.SERVICE_TYPE_REARVIEW, CarEvsManager.SERVICE_TYPE_SURROUNDVIEW,
        CarEvsManager.SERVICE_TYPE_FRONTVIEW, CarEvsManager.SERVICE_TYPE_LEFTVIEW,
        CarEvsManager.SERVICE_TYPE_RIGHTVIEW, CarEvsManager.SERVICE_TYPE_DRIVERVIEW,
        CarEvsManager.SERVICE_TYPE_FRONT_PASSENGERSVIEW,
        CarEvsManager.SERVICE_TYPE_REAR_PASSENGERSVIEW,
    };

    @Mock private ICarBase mMockCar;
    @Mock private CarEvsStatusListener mMockCarEvsStatusListener;
    @Mock private CarEvsStreamCallback mMockCarEvsStreamCallback;
    @Mock private IBinder mMockBinder;
    @Mock private ICarEvsService.Stub mMockICarEvsService;

    @Captor private ArgumentCaptor<ICarEvsStatusListener> mCarEvsStatusListenerCaptor;
    @Captor private ArgumentCaptor<ICarEvsStreamCallback> mCarEvsStreamCallbackCaptor;
    @Captor private ArgumentCaptor<CarEvsStatus> mCarEvsStatusCaptor;

    private final FakeFeatureFlagsImpl mFakeFeatureFlags = new FakeFeatureFlagsImpl();
    private CarEvsManager mManager;

    @Before
    public void setUp() throws Exception {
        mFakeFeatureFlags.setFlag(FLAG_CAR_EVS_STREAM_MANAGEMENT, true);
        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockICarEvsService);

        mManager = new CarEvsManager(mMockCar, mMockBinder, mFakeFeatureFlags);
        assertThat(mManager).isNotNull();
    }

    @After
    public void tearDown() throws Exception {
        mManager = null;
    }

    @Test
    public void testSetStatusListenerWithInvalidArguments() {
        assertThrows(NullPointerException.class,
                () -> mManager.setStatusListener(/* executor= */ null, /* listener= */ null));
        assertThrows(NullPointerException.class,
                () -> mManager.setStatusListener(/* executor= */ null, mMockCarEvsStatusListener));
    }

    @Test
    public void testSetStatusListenerWithValidArguments() throws Exception {
        mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener);
        verify(mMockICarEvsService, atLeastOnce()).registerStatusListener(any());
    }

    @Test
    public void testSetStatusListenerWithValidArgumentsTwice() throws Exception {
        mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener);

        verify(mMockICarEvsService, atLeastOnce()).registerStatusListener(any());
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener));
        assertWithMessage("Register CarEvsStatusListener")
                .that(thrown).hasMessageThat()
                .contains("A status listener is already registered.");
    }

    @Test
    public void testSetStatusListenerWithValidArgumentsRemoteExceptionThrown() throws Exception {
        doThrow(new RemoteException()).when(mMockICarEvsService).registerStatusListener(any());

        mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener);

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testStatusChangedEvent() throws Exception {
        doNothing().when(mMockICarEvsService)
                .registerStatusListener(mCarEvsStatusListenerCaptor.capture());

        mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener);
        ICarEvsStatusListener listener = mCarEvsStatusListenerCaptor.getValue();
        listener.onStatusChanged(new CarEvsStatus(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                                  CarEvsManager.SERVICE_STATE_INACTIVE));

        verify(mMockCarEvsStatusListener, atLeastOnce())
                .onStatusChanged(mCarEvsStatusCaptor.capture());
        CarEvsStatus received = mCarEvsStatusCaptor.getValue();
        assertThat(received.describeContents()).isEqualTo(0);
        assertThat(received.toString()).contains("CarEvsStatus:");
    }

    @Test
    public void testClearStatusListener() throws Exception {
        doNothing().when(mMockICarEvsService)
                .registerStatusListener(mCarEvsStatusListenerCaptor.capture());

        mManager.setStatusListener(Runnable::run, mMockCarEvsStatusListener);
        ICarEvsStatusListener listener = mCarEvsStatusListenerCaptor.getValue();
        mManager.clearStatusListener();

        verify(mMockICarEvsService, atLeastOnce()).unregisterStatusListener(listener);
    }

    @Test
    public void testClearStatusListenerRemoteExceptionThrown() throws Exception {
        doThrow(new RemoteException()).when(mMockICarEvsService).unregisterStatusListener(any());

        mManager.clearStatusListener();

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = HardwareBuffer.class)
    public void testStartVideoStreamWithoutToken() throws Exception {
        when(mMockICarEvsService
                .startVideoStream(anyInt(), any(), mCarEvsStreamCallbackCaptor.capture()))
                .thenReturn(ERROR_NONE);

        assertThat(mManager.startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                             /* token= */ null, Runnable::run,
                                             mMockCarEvsStreamCallback))
                .isEqualTo(ERROR_NONE);

        ICarEvsStreamCallback cb = mCarEvsStreamCallbackCaptor.getValue();
        cb.onStreamEvent(CarEvsManager.SERVICE_TYPE_REARVIEW,
                CarEvsManager.STREAM_EVENT_STREAM_STOPPED);
        verify(mMockCarEvsStreamCallback, atLeastOnce()).onStreamEvent(
                CarEvsManager.SERVICE_TYPE_REARVIEW, CarEvsManager.STREAM_EVENT_STREAM_STOPPED);

        int bufferId = 1;
        HardwareBuffer hwbuffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        CarEvsBufferDescriptor buffer = new CarEvsBufferDescriptor(bufferId,
                CarEvsManager.SERVICE_TYPE_REARVIEW, hwbuffer);
        cb.onNewFrame(buffer);
        verify(mMockCarEvsStreamCallback, atLeastOnce()).onNewFrame(buffer);
    }

    @Test
    public void testStartVideoStreamWithoutTokenRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.startVideoStream(anyInt(), any(), any()))
                .thenThrow(new RemoteException());

        assertThat(mManager.startVideoStream(/* type= */ CarEvsManager.SERVICE_TYPE_REARVIEW,
                                             /* token= */ null,
                                             /* executor= */ Runnable::run,
                                             /* callback= */ mMockCarEvsStreamCallback))
                .isEqualTo(ERROR_UNAVAILABLE);

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testStopVideoStream() throws Exception {
        mManager.stopVideoStream();
        verify(mMockICarEvsService, never()).stopVideoStream(any());

        assertThat(mManager.startVideoStream(/* type= */ CarEvsManager.SERVICE_TYPE_REARVIEW,
                                             /* token= */ null,
                                             /* executor= */ Runnable::run,
                                             /* callback= */ mMockCarEvsStreamCallback))
                .isEqualTo(ERROR_NONE);

        mManager.stopVideoStream();

        verify(mMockICarEvsService, atLeastOnce()).stopVideoStream(any());
    }

    @Test
    public void testStopVideoStreamRemoteExceptionThrown() throws Exception {
        doThrow(new RemoteException()).when(mMockICarEvsService).stopVideoStream(any());

        assertThat(mManager.startVideoStream(/* type= */ CarEvsManager.SERVICE_TYPE_REARVIEW,
                                             /* token= */ null,
                                             /* executor= */ Runnable::run,
                                             /* callback= */ mMockCarEvsStreamCallback))
                .isEqualTo(ERROR_NONE);
        mManager.stopVideoStream();

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testGetCurrentStatus() throws Exception {
        when(mMockICarEvsService.getCurrentStatus(anyInt()))
                .thenReturn(new CarEvsStatus(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                             CarEvsManager.SERVICE_STATE_INACTIVE));

        CarEvsStatus currentStatus = mManager.getCurrentStatus();

        assertThat(currentStatus).isNotNull();
        assertThat(currentStatus.getServiceType()).isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(currentStatus.getState()).isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
    }

    @Test
    public void testGetCurrentStatusWithType() throws Exception {
        for (int type : SERVICE_TYPES) {
            when(mMockICarEvsService.getCurrentStatus(anyInt()))
                    .thenReturn(new CarEvsStatus(type, CarEvsManager.SERVICE_STATE_INACTIVE));

            CarEvsStatus currentStatus = mManager.getCurrentStatus(type);

            assertThat(currentStatus).isNotNull();
            assertThat(currentStatus.getServiceType()).isEqualTo(type);
            assertThat(currentStatus.getState()).isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);
        }
    }

    @Test
    public void testGetCurrentStatusRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.getCurrentStatus(anyInt())).thenThrow(new RemoteException());

        CarEvsStatus currentStatus = mManager.getCurrentStatus();

        assertThat(currentStatus.getServiceType()).isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(currentStatus.getState()).isEqualTo(CarEvsManager.SERVICE_STATE_UNAVAILABLE);
    }

    @Test
    public void testGetCurrentStatusWithTypeRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.getCurrentStatus(anyInt())).thenThrow(new RemoteException());

        for (int type : SERVICE_TYPES) {
            assertThat(mManager.getCurrentStatus(type)).isNull();
        }
    }

    @Test
    public void testGenerateSessionToken() throws Exception {
        assertThat(mManager.generateSessionToken()).isNotNull();

        when(mMockICarEvsService.generateSessionToken()).thenReturn(null);
        assertThat(mManager.generateSessionToken()).isNotNull();
    }

    @Test
    public void testGenerateSessionTokenRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.generateSessionToken()).thenThrow(new RemoteException());

        assertThat(mManager.generateSessionToken()).isNotNull();
    }

    @Test
    public void testIsSupported() throws Exception {
        when(mMockICarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_REARVIEW)).thenReturn(true);
        when(mMockICarEvsService.isSupported(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW))
                .thenReturn(false);

        assertThat(mManager.isSupported(CarEvsManager.SERVICE_TYPE_REARVIEW)).isTrue();
        assertThat(mManager.isSupported(CarEvsManager.SERVICE_TYPE_SURROUNDVIEW)).isFalse();
    }

    @Test
    public void testIsSupportedRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.isSupported(anyInt())).thenThrow(new RemoteException());

        assertThat(mManager.isSupported(CarEvsManager.SERVICE_TYPE_REARVIEW)).isFalse();
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = HardwareBuffer.class)
    public void testReturnFrameBuffer() throws Exception {
        int bufferId = 1;
        HardwareBuffer hwbuffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        CarEvsBufferDescriptor buffer = new CarEvsBufferDescriptor(bufferId, hwbuffer);

        mManager.returnFrameBuffer(buffer);

        verify(mMockICarEvsService, atLeastOnce()).returnFrameBuffer(buffer);
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = HardwareBuffer.class)
    public void testReturnFrameBufferRemoteExceptionThrown() throws Exception {
        doThrow(new RemoteException()).when(mMockICarEvsService).returnFrameBuffer(any());
        int bufferId = 1;
        HardwareBuffer hwbuffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        CarEvsBufferDescriptor buffer = new CarEvsBufferDescriptor(bufferId, hwbuffer);

        mManager.returnFrameBuffer(buffer);

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testStartActivity() throws Exception {
        when(mMockICarEvsService.startActivity(anyInt())).thenReturn(ERROR_NONE);

        assertThat(mManager.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_NONE);

        verify(mMockICarEvsService, atLeastOnce()).startActivity(anyInt());
    }

    @Test
    public void testStartActivityRemoteExceptionThrown() throws Exception {
        when(mMockICarEvsService.startActivity(anyInt())).thenThrow(new RemoteException());

        assertThat(mManager.startActivity(CarEvsManager.SERVICE_TYPE_REARVIEW))
                .isEqualTo(ERROR_UNAVAILABLE);

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testStopActivity() throws Exception {
        mManager.stopActivity();

        verify(mMockICarEvsService, atLeastOnce()).stopActivity();
    }

    @Test
    public void testStopActivityRemoteExceptionThrown() throws Exception {
        doThrow(new RemoteException()).when(mMockICarEvsService).stopActivity();

        mManager.stopActivity();

        verify(mMockCar, atLeastOnce()).handleRemoteExceptionFromCarService(any());
    }

    @Test
    public void testOnCarDisconnected() {
        mManager.onCarDisconnected();
    }

    @Test
    public void testCarEvsStatus() {
        CarEvsStatus[] arr = CarEvsStatus.CREATOR.newArray(3);
        assertThat(arr.length).isEqualTo(3);

        CarEvsStatus original = new CarEvsStatus(CarEvsManager.SERVICE_TYPE_REARVIEW,
                                                 CarEvsManager.SERVICE_STATE_INACTIVE);
        assertThat(original.getServiceType()).isEqualTo(CarEvsManager.SERVICE_TYPE_REARVIEW);
        assertThat(original.getState()).isEqualTo(CarEvsManager.SERVICE_STATE_INACTIVE);

        android.os.Parcel packet = android.os.Parcel.obtain();
        original.writeToParcel(packet, /* flags= */ 0);
        assertThat(CarEvsStatus.CREATOR.createFromParcel(packet)).isNotNull();
    }

    @Test
    @IgnoreUnderRavenwood(blockedBy = HardwareBuffer.class)
    public void testCarEvsBufferDescriptor() {
        CarEvsBufferDescriptor[] arr = CarEvsBufferDescriptor.CREATOR.newArray(3);
        assertThat(arr.length).isEqualTo(3);

        int bufferId = 1;
        HardwareBuffer hwbuffer =
                HardwareBuffer.create(/* width= */ 64, /* height= */ 32,
                                      /* format= */ HardwareBuffer.RGBA_8888,
                                      /* layers= */ 1,
                                      /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN);
        CarEvsBufferDescriptor original = new CarEvsBufferDescriptor(bufferId, hwbuffer);

        assertThat(original.getId()).isEqualTo(bufferId);
        assertThat(original.getHardwareBuffer().getWidth()).isEqualTo(64);
        assertThat(original.getHardwareBuffer().getHeight()).isEqualTo(32);
        assertThat(original.getHardwareBuffer().getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);

        android.os.Parcel packet = android.os.Parcel.obtain();
        original.writeToParcel(packet, /* flags= */ 0);
        packet.setDataPosition(0);
        assertThat(CarEvsBufferDescriptor.CREATOR.createFromParcel(packet)).isNotNull();
    }
}
