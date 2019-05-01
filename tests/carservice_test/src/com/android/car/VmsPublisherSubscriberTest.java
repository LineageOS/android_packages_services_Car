/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.util.Pair;

import androidx.test.filters.MediumTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@MediumTest
public class VmsPublisherSubscriberTest extends MockedVmsTestBase {
    private static final int LAYER_ID = 88;
    private static final int LAYER_VERSION = 19;
    private static final int LAYER_SUBTYPE = 55;
    private static final String TAG = "VmsPubSubTest";

    // The expected publisher ID is 0 since it the expected assigned ID from the VMS core.
    public static final int EXPECTED_PUBLISHER_ID = 0;
    public static final VmsLayer LAYER = new VmsLayer(LAYER_ID, LAYER_SUBTYPE, LAYER_VERSION);
    public static final VmsAssociatedLayer ASSOCIATED_LAYER =
            new VmsAssociatedLayer(LAYER, new HashSet<>(Arrays.asList(EXPECTED_PUBLISHER_ID)));
    public static final byte[] PAYLOAD = new byte[]{2, 3, 5, 7, 11, 13, 17};

    private static final List<VmsAssociatedLayer> AVAILABLE_ASSOCIATED_LAYERS =
            new ArrayList<>(Arrays.asList(ASSOCIATED_LAYER));
    private static final VmsAvailableLayers AVAILABLE_LAYERS_WITH_SEQ =
            new VmsAvailableLayers(
                    new HashSet(AVAILABLE_ASSOCIATED_LAYERS), 1);


    private static final int SUBSCRIBED_LAYER_ID = 89;
    public static final VmsLayer SUBSCRIBED_LAYER =
            new VmsLayer(SUBSCRIBED_LAYER_ID, LAYER_SUBTYPE, LAYER_VERSION);
    public static final VmsAssociatedLayer ASSOCIATED_SUBSCRIBED_LAYER =
            new VmsAssociatedLayer(SUBSCRIBED_LAYER,
                    new HashSet<>(Arrays.asList(EXPECTED_PUBLISHER_ID)));
    private static final List<VmsAssociatedLayer>
            AVAILABLE_ASSOCIATED_LAYERS_WITH_SUBSCRIBED_LAYER =
            new ArrayList<>(Arrays.asList(ASSOCIATED_LAYER, ASSOCIATED_SUBSCRIBED_LAYER));
    private static final VmsAvailableLayers AVAILABLE_LAYERS_WITH_SUBSCRIBED_LAYER_WITH_SEQ =
            new VmsAvailableLayers(
                    new HashSet(AVAILABLE_ASSOCIATED_LAYERS_WITH_SUBSCRIBED_LAYER), 1);

    /*
     * This test method subscribes to a layer and triggers
     * VmsPublisherClientMockService.onVmsSubscriptionChange. In turn, the mock service will publish
     * a message, which is validated in this test.
     */
    @Test
    public void testPublisherToSubscriber() throws Exception {
        getSubscriberManager().subscribe(LAYER);

        int publisherId = getMockPublisherClient().getPublisherId(PAYLOAD);
        getMockPublisherClient().publish(LAYER, publisherId, PAYLOAD);

        Pair<VmsLayer, byte[]> dataMessage = receiveDataMessage();
        assertEquals(LAYER, dataMessage.first);
        assertArrayEquals(PAYLOAD, dataMessage.second);
    }

    /**
     * The Mock service will get a publisher ID by sending its information when it will get
     * ServiceReady as well as on SubscriptionChange. Since clients are not notified when
     * publishers are assigned IDs, this test waits until the availability is changed which
     * indicates
     * that the Mock service has gotten its ServiceReady and publisherId.
     */
    @Test
    public void testPublisherInfo() throws Exception {
        int publisherId = getMockPublisherClient().getPublisherId(PAYLOAD);
        byte[] info = getSubscriberManager().getPublisherInfo(publisherId);
        assertArrayEquals(PAYLOAD, info);
    }

    /*
     * The Mock service offers all the subscribed layers as available layers.
     * In this test the client subscribes to a layer and verifies that it gets the
     * notification that it is available.
     */
    @Test
    public void testAvailabilityWithSubscription() throws Exception {
        int publisherId = getMockPublisherClient().getPublisherId(PAYLOAD);
        getMockPublisherClient().setLayersOffering(new VmsLayersOffering(
                new HashSet<>(Arrays.asList(
                        new VmsLayerDependency(LAYER),
                        new VmsLayerDependency(SUBSCRIBED_LAYER))),
                publisherId));

        Set<VmsAssociatedLayer> associatedLayers =
                AVAILABLE_LAYERS_WITH_SUBSCRIBED_LAYER_WITH_SEQ.getAssociatedLayers();
        assertEquals(associatedLayers, receiveLayerAvailability().getAssociatedLayers());
        assertEquals(associatedLayers,
                getSubscriberManager().getAvailableLayers().getAssociatedLayers());
    }
}
