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

import android.car.vms.IVmsPublisherService;

/**
 * @hide
 */
interface IVmsPublisherClient {
    /**
    * Once the VmsPublisherService is bound to the client, this callback is used to set the
    * binder that the client can use to invoke publisher services.
    */
    oneway void setVmsPublisherService(IVmsPublisherService service) = 0;

    /**
     * The VmsPublisherService uses this callback to notify about subscription changes.
     */
    oneway void onVmsSubscriptionChange(int layer, int version, boolean hasSubscribers) = 1;
}
