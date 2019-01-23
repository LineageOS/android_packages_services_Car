/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.car.cluster.renderer;

import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

/**
 * Binder API for Instrument Cluster Navigation. This represents a direct communication channel
 * from navigation applications to the cluster vendor implementation.
 *
 * @hide
 */
interface IInstrumentClusterNavigation {
    /**
     * Called when an event is fired to change the navigation state. Content of this events can be
     * interpreted using androidx.car.car-cluster API.
     *
     * @param eventType type of navigation state change
     * @param bundle {@link android.os.Bundle} containing the description of the navigation state
     *               change.
     */
    void onEvent(int eventType, in Bundle bundle);

    /**
     * Returns attributes of instrument cluster for navigation.
     */
    CarNavigationInstrumentCluster getInstrumentClusterInfo();
}
