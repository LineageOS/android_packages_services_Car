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
package android.car.cluster;

import android.annotation.SystemApi;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.view.Display;

/**
 * Interface for instrument cluster rendering.
 *
 * TODO: implement instrument cluster feature list and extend API.
 *
 * @hide
 */
@SystemApi
public abstract class InstrumentClusterRenderer {

    private NavigationRenderer mNavigationRenderer;

    /**
     * Calls once when instrument cluster should be created.
     * @param context
     * @param display
     */
    abstract public void onCreate(Context context, Display display);

    abstract public void onStart();

    abstract public void onStop();

    /**
     * Returns properties of instrument cluster for navigation.
     */
    abstract public CarNavigationInstrumentCluster getNavigationProperties();

    abstract protected NavigationRenderer createNavigationRenderer();

    public NavigationRenderer getNavigationRenderer() {
        if (mNavigationRenderer == null) {
            mNavigationRenderer = createNavigationRenderer();
        }
        return mNavigationRenderer;
    }
}
