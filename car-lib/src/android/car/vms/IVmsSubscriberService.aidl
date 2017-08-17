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

import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsLayer;

/**
 * @hide
 */
interface IVmsSubscriberService {
    /**
     * Adds a subscriber to a VMS layer.
     */
    void addVmsSubscriber(
            in IVmsSubscriberClient subscriber,
            in VmsLayer layer) = 0;

    /**
     * Adds a subscriber to all actively broadcasted layers.
     * Publishers will not be notified regarding this request so the state of the service will not
     * change.
     */
    void addVmsSubscriberPassive(in IVmsSubscriberClient subscriber) = 1;

    /**
     * Adds a subscriber to a VMS layer from a specific publisher.
     */
    void addVmsSubscriberToPublisher(
            in IVmsSubscriberClient subscriber,
            in VmsLayer layer,
            int publisherId) = 2;

    /**
     * Removes a subscriber to a VMS layer.
     */
    void removeVmsSubscriber(
            in IVmsSubscriberClient subscriber,
            in VmsLayer layer) = 3;

    /**
     * Removes a subscriber to all actively broadcasted layers.
     * Publishers will not be notified regarding this request so the state of the service will not
     * change.
     */
    void removeVmsSubscriberPassive(
            in IVmsSubscriberClient subscriber) = 4;

    /**
     * Removes a subscriber to a VMS layer from a specific publisher.
     */
    void removeVmsSubscriberToPublisher(
            in IVmsSubscriberClient subscriber,
            in VmsLayer layer,
            int publisherId) = 5;

    /**
     * Returns a list of available layers from the closure of the publishers offerings.
     */
    List<VmsLayer> getAvailableLayers() = 6;

    /**
     *  Returns a the publisher information for a publisher ID.
     */
    byte[] getPublisherInfo(in int publisherId) = 7;
}
