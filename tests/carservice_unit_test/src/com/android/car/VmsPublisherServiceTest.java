/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsPublisherService;
import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayersOffering;
import android.car.vms.VmsSubscriptionState;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import com.android.car.stats.CarStatsService;
import com.android.car.stats.VmsClientLogger;
import com.android.car.vms.VmsBrokerService;
import com.android.car.vms.VmsClientManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

@SmallTest
public class VmsPublisherServiceTest {
    private static final VmsSubscriptionState SUBSCRIPTION_STATE = new VmsSubscriptionState(12345,
            Collections.emptySet(), Collections.emptySet());
    private static final VmsLayersOffering OFFERING = new VmsLayersOffering(Collections.emptySet(),
            54321);
    private static final VmsLayer LAYER = new VmsLayer(1, 2, 3);

    private static final int PUBLISHER_ID = 54321;
    private static final byte[] PAYLOAD = new byte[]{1, 2, 3, 4};

    private static final int PUBLISHER_UID = 10100;
    private static final int SUBSCRIBER_UID = 10101;
    private static final int SUBSCRIBER_UID2 = 10102;
    private static final int NO_SUBSCRIBERS_UID = -1;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Mock
    private CarStatsService mStatsService;
    @Mock
    private VmsBrokerService mBrokerService;
    @Captor
    private ArgumentCaptor<VmsBrokerService.PublisherListener> mProxyCaptor;
    @Mock
    private VmsClientManager mClientManager;

    @Mock
    private VmsClientLogger mPublisherLog;
    @Mock
    private VmsClientLogger mSubscriberLog;
    @Mock
    private VmsClientLogger mSubscriberLog2;
    @Mock
    private VmsClientLogger mNoSubscribersLog;

    @Mock
    private IVmsSubscriberClient mSubscriberClient;
    @Mock
    private IVmsSubscriberClient mSubscriberClient2;

    private VmsPublisherService mPublisherService;
    private MockPublisherClient mPublisherClient;
    private MockPublisherClient mPublisherClient2;

    @Before
    public void setUp() {
        mPublisherService = new VmsPublisherService(mContext, mStatsService, mBrokerService,
                mClientManager, () -> PUBLISHER_UID);
        verify(mClientManager).setPublisherService(mPublisherService);

        when(mClientManager.getSubscriberUid(mSubscriberClient)).thenReturn(SUBSCRIBER_UID);
        when(mClientManager.getSubscriberUid(mSubscriberClient2)).thenReturn(SUBSCRIBER_UID2);

        when(mStatsService.getVmsClientLogger(PUBLISHER_UID)).thenReturn(mPublisherLog);
        when(mStatsService.getVmsClientLogger(SUBSCRIBER_UID)).thenReturn(mSubscriberLog);
        when(mStatsService.getVmsClientLogger(SUBSCRIBER_UID2)).thenReturn(mSubscriberLog2);
        when(mStatsService.getVmsClientLogger(NO_SUBSCRIBERS_UID)).thenReturn(mNoSubscribersLog);

        mPublisherClient = new MockPublisherClient();
        mPublisherClient2 = new MockPublisherClient();
        when(mBrokerService.getSubscribersForLayerFromPublisher(LAYER, PUBLISHER_ID))
                .thenReturn(new HashSet<>(Arrays.asList(mSubscriberClient, mSubscriberClient2)));
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mPublisherLog, mSubscriberLog, mSubscriberLog2, mNoSubscribersLog);
    }

    @Test
    public void testInit() {
        mPublisherService.init();
    }

    @Test
    public void testOnClientConnected() {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientConnected("SomeOtherClient", mPublisherClient2);
        verify(mBrokerService, times(2)).addPublisherListener(mProxyCaptor.capture());

        assertNotNull(mPublisherClient.mPublisherService);
        assertSame(mPublisherClient.mPublisherService, mProxyCaptor.getAllValues().get(0));

        assertNotNull(mPublisherClient2.mPublisherService);
        assertSame(mPublisherClient2.mPublisherService, mProxyCaptor.getAllValues().get(1));
        assertNotSame(mPublisherClient2.mPublisherService, mPublisherClient.mPublisherService);
    }

    @Test
    public void testOnClientDisconnected() {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientConnected("SomeOtherClient", mPublisherClient2);
        verify(mBrokerService, times(2)).addPublisherListener(mProxyCaptor.capture());

        reset(mClientManager, mBrokerService);
        mPublisherService.onClientDisconnected("SomeClient");

        verify(mBrokerService).removeDeadPublisher(mPublisherClient.mToken);
        verify(mBrokerService).removePublisherListener(mProxyCaptor.getAllValues().get(0));
        verifyNoMoreInteractions(mBrokerService);
    }

    @Test
    public void testSetLayersOffering() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);

        mPublisherClient.mPublisherService.setLayersOffering(mPublisherClient.mToken, OFFERING);
        verify(mBrokerService).setPublisherLayersOffering(mPublisherClient.mToken, OFFERING);
    }

    @Test(expected = SecurityException.class)
    public void testSetLayersOffering_InvalidToken() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);

        mPublisherClient.mPublisherService.setLayersOffering(new Binder(), OFFERING);
    }

    @Test(expected = SecurityException.class)
    public void testSetLayersOffering_Disconnected() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientDisconnected("SomeClient");

        mPublisherClient.mPublisherService.setLayersOffering(mPublisherClient.mToken, OFFERING);
    }

    @Test(expected = SecurityException.class)
    public void testSetLayersOffering_PermissionDenied() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VMS_PUBLISHER)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mPublisherClient.mPublisherService.setLayersOffering(mPublisherClient.mToken, OFFERING);
    }

    @Test
    public void testPublish() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);

        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, LAYER, PUBLISHER_ID,
                PAYLOAD);
        verify(mSubscriberClient).onVmsMessageReceived(LAYER, PAYLOAD);
        verify(mSubscriberClient2).onVmsMessageReceived(LAYER, PAYLOAD);

        verify(mPublisherLog).logPacketSent(LAYER, PAYLOAD.length);
        verify(mSubscriberLog).logPacketReceived(LAYER, PAYLOAD.length);
        verify(mSubscriberLog2).logPacketReceived(LAYER, PAYLOAD.length);
    }

    @Test
    public void testPublishNullLayerAndNullPayload() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);

        // We just want to ensure that no exceptions are thrown here.
        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, null, PUBLISHER_ID,
                null);
    }

    @Test
    public void testPublish_NoSubscribers() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mBrokerService.getSubscribersForLayerFromPublisher(LAYER, PUBLISHER_ID))
                .thenReturn(Collections.emptySet());

        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, LAYER, PUBLISHER_ID,
                PAYLOAD);

        verify(mPublisherLog).logPacketSent(LAYER, PAYLOAD.length);
        verify(mNoSubscribersLog).logPacketDropped(LAYER, PAYLOAD.length);
    }

    @Test
    public void testPublish_ClientError() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        doThrow(new RemoteException()).when(mSubscriberClient).onVmsMessageReceived(LAYER, PAYLOAD);

        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, LAYER, PUBLISHER_ID,
                PAYLOAD);
        verify(mSubscriberClient).onVmsMessageReceived(LAYER, PAYLOAD);
        verify(mSubscriberClient2).onVmsMessageReceived(LAYER, PAYLOAD);

        verify(mPublisherLog).logPacketSent(LAYER, PAYLOAD.length);
        verify(mSubscriberLog).logPacketDropped(LAYER, PAYLOAD.length);
        verify(mSubscriberLog2).logPacketReceived(LAYER, PAYLOAD.length);
    }

    @Test(expected = SecurityException.class)
    public void testPublish_InvalidToken() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);

        mPublisherClient.mPublisherService.publish(new Binder(), LAYER, PUBLISHER_ID, PAYLOAD);
    }

    @Test(expected = SecurityException.class)
    public void testPublish_Disconnected() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientDisconnected("SomeClient");

        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, LAYER, PUBLISHER_ID,
                PAYLOAD);
    }

    @Test(expected = SecurityException.class)
    public void testPublish_PermissionDenied() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VMS_PUBLISHER)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mPublisherClient.mPublisherService.publish(mPublisherClient.mToken, LAYER, PUBLISHER_ID,
                PAYLOAD);
    }

    @Test
    public void testGetSubscriptions() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mBrokerService.getSubscriptionState()).thenReturn(SUBSCRIPTION_STATE);

        assertEquals(SUBSCRIPTION_STATE, mPublisherClient.mPublisherService.getSubscriptions());
    }

    @Test(expected = SecurityException.class)
    public void testGetSubscriptions_Disconnected() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientDisconnected("SomeClient");

        mPublisherClient.mPublisherService.getSubscriptions();
    }

    @Test(expected = SecurityException.class)
    public void testGetSubscriptions_PermissionDenied() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VMS_PUBLISHER)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mPublisherClient.mPublisherService.getSubscriptions();
    }

    @Test
    public void testGetPublisherId() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mBrokerService.getPublisherId(PAYLOAD)).thenReturn(PUBLISHER_ID);

        assertEquals(PUBLISHER_ID, mPublisherClient.mPublisherService.getPublisherId(PAYLOAD));
    }

    @Test(expected = SecurityException.class)
    public void testGetPublisherId_Disconnected() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientDisconnected("SomeClient");

        mPublisherClient.mPublisherService.getPublisherId(PAYLOAD);
    }

    @Test(expected = SecurityException.class)
    public void testGetPublisherId_PermissionDenied() throws Exception {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        when(mContext.checkCallingOrSelfPermission(Car.PERMISSION_VMS_PUBLISHER)).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mPublisherClient.mPublisherService.getPublisherId(PAYLOAD);
    }

    @Test
    public void testOnSubscriptionChange() {
        mPublisherService.onClientConnected("SomeClient", mPublisherClient);
        mPublisherService.onClientConnected("SomeOtherClient", mPublisherClient2);
        verify(mBrokerService, times(2)).addPublisherListener(mProxyCaptor.capture());

        mProxyCaptor.getAllValues().get(0).onSubscriptionChange(SUBSCRIPTION_STATE);

        assertEquals(SUBSCRIPTION_STATE, mPublisherClient.mSubscriptionState);
        assertNull(mPublisherClient2.mSubscriptionState);
    }

    @Test
    public void testRelease() {
        mPublisherService.release();
    }

    private class MockPublisherClient extends IVmsPublisherClient.Stub {
        private IBinder mToken;
        private IVmsPublisherService mPublisherService;
        private VmsSubscriptionState mSubscriptionState;

        @Override
        public void setVmsPublisherService(IBinder token, IVmsPublisherService service) {
            assertNotNull(token);
            assertNotNull(service);
            if (mToken != null) {
                throw new IllegalArgumentException("Publisher service set multiple times");
            }
            this.mToken = token;
            this.mPublisherService = service;
        }

        @Override
        public void onVmsSubscriptionChange(VmsSubscriptionState subscriptionState) {
            assertNotNull(subscriptionState);
            this.mSubscriptionState = subscriptionState;
        }
    }
}
