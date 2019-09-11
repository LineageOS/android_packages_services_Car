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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.content.Context;

import androidx.test.filters.SmallTest;

import com.android.car.hal.VmsHalService;
import com.android.car.vms.VmsBrokerService;
import com.android.car.vms.VmsClientManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Collections;

@SmallTest
public class VmsSubscriberServiceTest {
    private static final VmsLayer LAYER = new VmsLayer(1, 2, 3);
    private static final int PUBLISHER_ID = 54321;
    private static final byte[] PUBLISHER_INFO = new byte[]{1, 2, 3, 4};
    private static final VmsAvailableLayers AVAILABLE_LAYERS =
            new VmsAvailableLayers(Collections.emptySet(), 0);

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private Context mContext;
    @Mock
    private VmsBrokerService mBrokerService;
    @Mock
    private VmsClientManager mClientManager;
    @Mock
    private VmsHalService mHal;

    @Mock
    private IVmsSubscriberClient mSubscriberClient;
    @Mock
    private IVmsSubscriberClient mSubscriberClient2;

    private VmsSubscriberService mSubscriberService;

    @Before
    public void setUp() {
        mSubscriberService = new VmsSubscriberService(mContext, mBrokerService, mClientManager,
                mHal);
        verify(mBrokerService).addSubscriberListener(eq(mSubscriberService));
        verify(mHal).setVmsSubscriberService(eq(mSubscriberService));
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mBrokerService, mClientManager);
    }

    @Test
    public void testAddVmsSubscriberToNotifications() {
        mSubscriberService.addVmsSubscriberToNotifications(mSubscriberClient);
        verify(mClientManager).addSubscriber(mSubscriberClient);
    }

    @Test
    public void testRemoveVmsSubscriberToNotifications() {
        mSubscriberService.removeVmsSubscriberToNotifications(mSubscriberClient);
        verify(mClientManager).removeSubscriber(mSubscriberClient);
    }

    @Test
    public void testAddVmsSubscriber() {
        mSubscriberService.addVmsSubscriber(mSubscriberClient, LAYER);
        verify(mClientManager).addSubscriber(mSubscriberClient);
        verify(mBrokerService).addSubscription(mSubscriberClient, LAYER);
    }

    @Test
    public void testRemoveVmsSubscriber() {
        mSubscriberService.removeVmsSubscriber(mSubscriberClient, LAYER);
        verify(mBrokerService).removeSubscription(mSubscriberClient, LAYER);
    }


    @Test
    public void testAddVmsSubscriberToPublisher() {
        mSubscriberService.addVmsSubscriberToPublisher(mSubscriberClient, LAYER, PUBLISHER_ID);
        verify(mClientManager).addSubscriber(mSubscriberClient);
        verify(mBrokerService).addSubscription(mSubscriberClient, LAYER, PUBLISHER_ID);
    }

    @Test
    public void testRemoveVmsSubscriberToPublisher() {
        testAddVmsSubscriberToPublisher();

        mSubscriberService.removeVmsSubscriberToPublisher(mSubscriberClient, LAYER, PUBLISHER_ID);
        verify(mBrokerService).removeSubscription(mSubscriberClient, LAYER, PUBLISHER_ID);
    }

    @Test
    public void testAddVmsSubscriberPassive() {
        mSubscriberService.addVmsSubscriberPassive(mSubscriberClient);
        verify(mClientManager).addSubscriber(mSubscriberClient);
        verify(mBrokerService).addSubscription(mSubscriberClient);
    }

    @Test
    public void testRemoveVmsSubscriberPassive() {
        mSubscriberService.removeVmsSubscriberPassive(mSubscriberClient);
        verify(mBrokerService).removeSubscription(mSubscriberClient);
    }

    @Test
    public void testGetPublisherInfo() {
        when(mBrokerService.getPublisherInfo(PUBLISHER_ID)).thenReturn(PUBLISHER_INFO);
        assertThat(mSubscriberService.getPublisherInfo(PUBLISHER_ID)).isSameAs(PUBLISHER_INFO);
        verify(mBrokerService).getPublisherInfo(PUBLISHER_ID);
    }

    @Test
    public void testGetAvailableLayers() {
        when(mBrokerService.getAvailableLayers()).thenReturn(AVAILABLE_LAYERS);
        assertThat(mSubscriberService.getAvailableLayers()).isSameAs(AVAILABLE_LAYERS);
        verify(mBrokerService).getAvailableLayers();
    }

    @Test
    public void testOnLayersAvailabilityChange() throws Exception {
        when(mClientManager.getAllSubscribers())
                .thenReturn(Arrays.asList(mSubscriberClient, mSubscriberClient2));
        mSubscriberService.onLayersAvailabilityChange(AVAILABLE_LAYERS);
        verify(mClientManager).getAllSubscribers();
        verify(mSubscriberClient).onLayersAvailabilityChanged(AVAILABLE_LAYERS);
        verify(mSubscriberClient2).onLayersAvailabilityChanged(AVAILABLE_LAYERS);
    }
}
