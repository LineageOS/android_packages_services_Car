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

package android.car.vms;

import android.car.vms.IOnVmsMessageReceivedListener;

/**
 * @hide
 */
interface IVmsSubscriberService {
    /**
     * Subscribes the listener to receive messages from layer/version.
     */
    void addOnVmsMessageReceivedListener(
            in IOnVmsMessageReceivedListener listener,
            int layer,
            int version) = 0;

    /**
     * Subscribes the listener to receive messages from all published layer/version. The
     * service will not send any subscription notifications to publishers (i.e. this is a passive
     * subscriber).
     */
    void addOnVmsMessageReceivedPassiveListener(in IOnVmsMessageReceivedListener listener) = 1;

    /**
     * Tells the VmsSubscriberService a client unsubscribes to layer messages.
     */
    void removeOnVmsMessageReceivedListener(
            in IOnVmsMessageReceivedListener listener,
            int layer,
            int version) = 2;

    /**
     * Tells the VmsSubscriberService a passive client unsubscribes. This will not unsubscribe
     * the listener from any specific layer it has subscribed to.
     */
    void removeOnVmsMessageReceivedPassiveListener(
            in IOnVmsMessageReceivedListener listener) = 3;
}
