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
import android.car.vms.VmsPublisherClientService;

import java.util.List;

/**
 * This service is launched during the tests in VmsPublisherClientServiceTest.
 */
@FutureFeature
public class SimpleVmsPublisherClientService extends VmsPublisherClientService {
    @Override
    public void onVmsSubscriptionChange(List<VmsLayer> layers, long sequence) {

    }

    @Override
    public void onVmsPublisherServiceReady() {
        // Publish a property that is going to be verified in the test.
        publish(VmsPublisherClientServiceTest.MOCK_PUBLISHER_LAYER,
                VmsPublisherClientServiceTest.PAYLOAD);
    }
}
