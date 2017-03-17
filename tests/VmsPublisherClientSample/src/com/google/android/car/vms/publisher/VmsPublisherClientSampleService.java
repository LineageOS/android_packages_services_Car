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

package com.google.android.car.vms.publisher;

import android.car.vms.VmsLayer;
import android.car.vms.VmsPublisherClientService;
import android.os.Handler;
import android.os.Message;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This service is launched during the initialization of the VMS publisher service.
 * Once onVmsPublisherServiceReady is invoked, it starts publishing a single byte every second.
 */
public class VmsPublisherClientSampleService extends VmsPublisherClientService {
    public static final int PUBLISH_EVENT = 0;
    public static final int TEST_LAYER_ID = 0;
    public static final int TEST_LAYER_VERSION = 0;

    private byte mCounter = 0;
    private AtomicBoolean mInitialized = new AtomicBoolean(false);

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == PUBLISH_EVENT) {
                periodicPublish();
            }
        }
    };

    /**
     * Notifies that the publisher services are ready to be used: {@link #publish(int, int, byte[])}
     * and {@link #getSubscribers()}.
     */
    @Override
    public void onVmsPublisherServiceReady() {
    }

    @Override
    public void onVmsSubscriptionChange(List<VmsLayer> layers, long sequence) {
        if (mInitialized.compareAndSet(false, true)) {
            for (VmsLayer layer : layers) {
                if (layer.getId() == TEST_LAYER_ID && layer.getVersion() == TEST_LAYER_VERSION) {
                    mHandler.sendEmptyMessage(PUBLISH_EVENT);
                }
            }
        }
    }

    private void periodicPublish() {
        publish(TEST_LAYER_ID, TEST_LAYER_VERSION, new byte[]{mCounter});
        ++mCounter;
        mHandler.sendEmptyMessageDelayed(PUBLISH_EVENT, 1000);
    }
}
