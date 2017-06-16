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

package com.android.car.test;

import android.car.annotation.FutureFeature;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayerDependency;
import android.car.vms.VmsLayersOffering;
import android.car.vms.VmsPublisherClientService;
import android.car.vms.VmsSubscriptionState;
import android.util.Log;
import java.util.List;
import java.util.ArrayList;

/**
 * This service is launched during the tests in VmsPublisherSubscriberTest. It publishes a property
 * that is going to be verified in the test.
 *
 * Note that the subscriber can subscribe before the publisher finishes initialization. To cover
 * both potential scenarios, this service publishes the test message in onVmsSubscriptionChange
 * and in onVmsPublisherServiceReady. See comments below.
 */
@FutureFeature
public class VmsPublisherClientMockService extends VmsPublisherClientService {
    private static final String TAG = "VmsPublisherClientMockService";

    @Override
    public void onVmsSubscriptionChange(VmsSubscriptionState subscriptionState) {
        // Case when the publisher finished initialization before the subscription request.
        initializeMockPublisher(subscriptionState);
    }

    @Override
    public void onVmsPublisherServiceReady() {
        // Case when the subscription request was sent before the publisher was ready.
        VmsSubscriptionState subscriptionState = getSubscriptions();
        initializeMockPublisher(subscriptionState);
    }

    private void initializeMockPublisher(VmsSubscriptionState subscriptionState) {
        Log.d(TAG, "Initializing Mock publisher");
        int publisherId = getPublisherStaticId(VmsPublisherSubscriberTest.PAYLOAD);
        publishIfNeeded(subscriptionState);
        declareOffering(subscriptionState, publisherId);
    }

    private void publishIfNeeded(VmsSubscriptionState subscriptionState) {
        for (VmsLayer layer : subscriptionState.getLayers()) {
            if (layer.equals(VmsPublisherSubscriberTest.LAYER)) {
                publish(VmsPublisherSubscriberTest.LAYER, VmsPublisherSubscriberTest.PAYLOAD);
            }
        }
    }

    private void declareOffering(VmsSubscriptionState subscriptionState, int publisherId) {
        List<VmsLayerDependency> dependencies = new ArrayList<>();
        for( VmsLayer layer : subscriptionState.getLayers()) {
            dependencies.add(new VmsLayerDependency(layer));
        }
        VmsLayersOffering offering = new VmsLayersOffering(dependencies, publisherId);
        setLayersOffering(offering);
    }
}
