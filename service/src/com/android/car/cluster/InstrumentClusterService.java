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
package com.android.car.cluster;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.car.cluster.renderer.InstrumentClusterRenderer;
import android.car.cluster.renderer.NavigationRenderer;
import android.content.Context;
import android.util.Log;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;

import java.io.PrintWriter;

/**
 * Service responsible for interaction with car's instrument cluster.
 *
 * @hide
 */
@SystemApi
public class InstrumentClusterService implements CarServiceBase {

    private static final String TAG = CarLog.TAG_CLUSTER + "."
            + InstrumentClusterService.class.getSimpleName();

    private final Context mContext;

    private InstrumentClusterRenderer mRenderer;

    public InstrumentClusterService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {
        Log.d(TAG, "init");

        boolean rendererFound = InstrumentClusterRendererLoader.isRendererAvailable(mContext);

        if (rendererFound) {
            mRenderer = InstrumentClusterRendererLoader.createRenderer(mContext);
            mRenderer.onCreate(mContext);
            mRenderer.initialize();
            mRenderer.onStart();
        }
    }

    @Override
    public void release() {
        Log.d(TAG, "release");
        if (mRenderer != null) {
            mRenderer.onStop();
            mRenderer = null;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("**" + getClass().getSimpleName() + "**");
        writer.println("InstrumentClusterRenderer: " + mRenderer);
        writer.println("NavigationRenderer: "
                + (mRenderer != null ? mRenderer.getNavigationRenderer() : null));
    }

    @Nullable
    public NavigationRenderer getNavigationRenderer() {
        return mRenderer != null ? mRenderer.getNavigationRenderer() : null;
    }
}
