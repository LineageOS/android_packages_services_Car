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

package com.android.car.remoteaccess;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.when;

import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.android.car.R;
import com.android.car.remoteaccess.hal.RemoteAccessHalCallback;
import com.android.car.remoteaccess.hal.RemoteAccessHalWrapper;
import com.android.compatibility.common.util.PollingCheck;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Objects;

@RunWith(MockitoJUnitRunner.class)
public final class CarRemoteAccessServiceUnitTest {

    private static final long WAIT_TIMEOUT_MS = 5000;
    private static final String WAKEUP_SERVICE_NAME = "android_wakeup_service";
    private static final String TEST_DEVICE_ID = "test_vehicle";

    private CarRemoteAccessService mService;
    private ICarRemoteAccessCallbackImpl mRemoteAccessCallback;

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private PackageManager mPackageManager;
    @Mock private RemoteAccessHalWrapper mRemoteAccessHal;

    @Before
    public void setUp() {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.config_allowedSystemUpTimeForRemoteAccess))
                .thenReturn(300);
        when(mRemoteAccessHal.getWakeupServiceName()).thenReturn(WAKEUP_SERVICE_NAME);
        when(mRemoteAccessHal.getDeviceId()).thenReturn(TEST_DEVICE_ID);
        mService = new CarRemoteAccessService(mContext, mRemoteAccessHal);
        mService.init();
        mRemoteAccessCallback = new ICarRemoteAccessCallbackImpl();
    }

    @Test
    public void testAddCarRemoteTaskClient() throws Exception {
        String packageName = "com.android.remoteaccess.unittest";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageName);

        mService.addCarRemoteTaskClient(mRemoteAccessCallback);

        PollingCheck.check("onClientRegistrationUpdated should be called", WAIT_TIMEOUT_MS,
                () -> Objects.equals(mRemoteAccessCallback.getServiceName(), WAKEUP_SERVICE_NAME)
                        && Objects.equals(mRemoteAccessCallback.getDeviceId(), TEST_DEVICE_ID)
                        && mRemoteAccessCallback.getClientId() != null);
    }

    @Test
    public void testAddCarRemoteTaskClient_addTwice() throws Exception {
        String packageName = "com.android.remoteaccess.unittest";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageName);
        ICarRemoteAccessCallbackImpl secondCallback = new ICarRemoteAccessCallbackImpl();
        mService.addCarRemoteTaskClient(mRemoteAccessCallback);

        mService.addCarRemoteTaskClient(secondCallback);

        PollingCheck.check("onClientRegistrationUpdated should be called", WAIT_TIMEOUT_MS,
                () -> Objects.equals(secondCallback.getServiceName(), WAKEUP_SERVICE_NAME)
                        && Objects.equals(secondCallback.getDeviceId(), TEST_DEVICE_ID)
                        && secondCallback.getClientId() != null);
    }

    @Test
    public void testAddCarRemoteTaskClient_addMultipleClients() throws Exception {
        String packageNameOne = "com.android.remoteaccess.unittest.one";
        String packageNameTwo = "com.android.remoteaccess.unittest.two";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageNameOne)
                .thenReturn(packageNameTwo);
        ICarRemoteAccessCallbackImpl secondCallback = new ICarRemoteAccessCallbackImpl();

        mService.addCarRemoteTaskClient(mRemoteAccessCallback);
        mService.addCarRemoteTaskClient(secondCallback);

        PollingCheck.check("Two clients should have different client IDs", WAIT_TIMEOUT_MS,
                () -> {
                    String clientIdOne = mRemoteAccessCallback.getClientId();
                    String clientIdTwo = secondCallback.getClientId();
                    return clientIdOne != null && !clientIdOne.equals(clientIdTwo);
                });
    }

    @Test
    public void testRemoveCarRemoteTaskClient() throws Exception {
        String packageName = "com.android.remoteaccess.unittest";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageName);
        mService.addCarRemoteTaskClient(mRemoteAccessCallback);

        mService.removeCarRemoteTaskClient(mRemoteAccessCallback);
    }

    @Test
    public void testRemoveCarRemoteTaskClient_removeNotAddedClient() throws Exception {
        // Removing unregistered ICarRemoteAccessCallback is no-op.
        mService.removeCarRemoteTaskClient(mRemoteAccessCallback);
    }

    @Test
    public void testRemoteTaskRequested() throws Exception {
        String packageName = "com.android.remoteaccess.unittest";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageName);
        RemoteAccessHalCallback halCallback = mService.getRemoteAccessHalCallback();
        mService.addCarRemoteTaskClient(mRemoteAccessCallback);
        PollingCheck.check("Client is registered", WAIT_TIMEOUT_MS,
                () -> mRemoteAccessCallback.getClientId() != null);

        String clientId = mRemoteAccessCallback.getClientId();
        byte[] data = new byte[]{1, 2, 3, 4};
        halCallback.onRemoteTaskRequested(clientId, data);

        PollingCheck.check("onRemoteTaskRequested should be called", WAIT_TIMEOUT_MS,
                () -> mRemoteAccessCallback.getTaskId() != null);
        assertWithMessage("Data").that(mRemoteAccessCallback.getData()).asList()
                .containsExactlyElementsIn(new Byte[]{1, 2, 3, 4});
    }

    @Test
    public void testRemoteTaskRequested_removedClient() throws Exception {
        String packageName = "com.android.remoteaccess.unittest";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageName);
        RemoteAccessHalCallback halCallback = mService.getRemoteAccessHalCallback();
        mService.addCarRemoteTaskClient(mRemoteAccessCallback);
        PollingCheck.check("Client is registered", WAIT_TIMEOUT_MS,
                () -> mRemoteAccessCallback.getClientId() != null);
        String clientId = mRemoteAccessCallback.getClientId();
        mService.removeCarRemoteTaskClient(mRemoteAccessCallback);

        halCallback.onRemoteTaskRequested(clientId, /* data= */ null);

        assertWithMessage("Task ID").that(mRemoteAccessCallback.getTaskId()).isNull();
    }

    @Test
    public void testRemoteTaskRequested_withTwoClientsRegistered() throws Exception {
        String packageNameOne = "com.android.remoteaccess.unittest.one";
        String packageNameTwo = "com.android.remoteaccess.unittest.two";
        when(mPackageManager.getNameForUid(anyInt())).thenReturn(packageNameOne)
                .thenReturn(packageNameTwo);
        ICarRemoteAccessCallbackImpl secondCallback = new ICarRemoteAccessCallbackImpl();
        mService.addCarRemoteTaskClient(mRemoteAccessCallback);
        mService.addCarRemoteTaskClient(secondCallback);
        PollingCheck.check("Client is registered", WAIT_TIMEOUT_MS,
                () -> mRemoteAccessCallback.getClientId() != null);
        PollingCheck.check("Client is registered", WAIT_TIMEOUT_MS,
                () -> secondCallback.getClientId() != null);
        String clientId = mRemoteAccessCallback.getClientId();
        mService.removeCarRemoteTaskClient(secondCallback);
        RemoteAccessHalCallback halCallback = mService.getRemoteAccessHalCallback();

        halCallback.onRemoteTaskRequested(clientId, /* data= */ null);

        PollingCheck.check("onRemoteTaskRequested should be called", WAIT_TIMEOUT_MS,
                () -> mRemoteAccessCallback.getTaskId() != null);
        assertWithMessage("Task ID").that(secondCallback.getTaskId()).isNull();
    }

    private static final class ICarRemoteAccessCallbackImpl extends ICarRemoteAccessCallback.Stub {
        private String mServiceName;
        private String mDeviceId;
        private String mClientId;
        private String mTaskId;
        private byte[] mData;
        private boolean mShutdownStarted;

        @Override
        public void onClientRegistrationUpdated(String serviceId, String deviceId,
                String clientId) {
            mServiceName = serviceId;
            mDeviceId = deviceId;
            mClientId = clientId;
        }

        @Override
        public void onClientRegistrationFailed() {
        }

        @Override
        public void onRemoteTaskRequested(String clientId, String taskId, byte[] data,
                int taskMaxDurationInSec) {
            mClientId = clientId;
            mTaskId = taskId;
            mData = data;
        }

        @Override
        public void onShutdownStarting() {
            mShutdownStarted = true;
        }

        public String getServiceName() {
            return mServiceName;
        }

        public String getDeviceId() {
            return mDeviceId;
        }

        public String getClientId() {
            return mClientId;
        }

        public String getTaskId() {
            return mTaskId;
        }

        public byte[] getData() {
            return mData;
        }
    }
}
