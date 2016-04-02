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
package android.car.cluster.loggingrenderer;

import android.car.cluster.renderer.InstrumentClusterRenderer;
import android.car.cluster.renderer.NavigationRenderer;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

/**
 * Dummy implementation of {@code InstrumentClusterRenderer} that just traces all interaction.
 */
public class LoggingInstrumentClusterRenderer extends InstrumentClusterRenderer {

    private final static String TAG = LoggingInstrumentClusterRenderer.class.getSimpleName();

    @Override
    public void onCreate(Context context) {
        Log.i(TAG, "onCreate, context: " + context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "onStart");
    }

    @Override
    public void onStop() {
        Log.i(TAG, "onStop");
    }

    @Override
    protected NavigationRenderer createNavigationRenderer() {
        NavigationRenderer navigationRenderer = new NavigationRenderer() {
            @Override
            public CarNavigationInstrumentCluster getNavigationProperties() {
                Log.i(TAG, "getNavigationProperties");
                CarNavigationInstrumentCluster config =
                        CarNavigationInstrumentCluster.createCluster(1000);
                Log.i(TAG, "getNavigationProperties, returns: " + config);
                return config;
            }


            @Override
            public void onStartNavigation() {
                Log.i(TAG, "onStartNavigation");
            }

            @Override
            public void onStopNavigation() {
                Log.i(TAG, "onStopNavigation");
            }

            @Override
            public void onNextTurnChanged(int event, String road, int turnAngle, int turnNumber,
                    Bitmap image, int turnSide) {
                Log.i(TAG, "event: " + event + ", road: " + road + ", turnAngle: " + turnAngle
                        + ", turnNumber: " + turnNumber + ", image: " + image + ", turnSide: "
                        + turnSide);
            }

            @Override
            public void onNextTurnDistanceChanged(int distanceMeters, int timeSeconds) {
                Log.i(TAG, "onNextTurnDistanceChanged, distanceMeters: " + distanceMeters
                        + ", timeSeconds: " + timeSeconds);
            }
        };

        Log.i(TAG, "createNavigationRenderer, returns: " + navigationRenderer);
        return navigationRenderer;
    }
}
