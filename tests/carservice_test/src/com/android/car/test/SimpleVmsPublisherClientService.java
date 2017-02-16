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
import android.car.vms.VmsPublisherClientService;

/**
 * This service is launched during the tests in VmsPublisherClientServiceTest.
 */
@FutureFeature
public class SimpleVmsPublisherClientService extends VmsPublisherClientService {
    @Override
    public void onVmsSubscriptionChange(int layer, int version, boolean hasSubscribers) {

    }

    @Override
    public void onVmsPublisherServiceReady() {
        // Publish a property that is going to be verified in the test.
        publish(getLayerId(), getLayerVersion(), getPayload());
    }

    // methods exposed for testing.
    static public int getLayerId() {
        return 12;
    }

    static public int getLayerVersion() {
        return 34;
    }

    static byte[] getPayload() {
        return new byte[]{1, 1, 2, 3, 5, 8, 13};
    }
}
