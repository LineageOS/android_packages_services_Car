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
package android.car.cluster.demorenderer;

import android.car.cluster.renderer.DisplayConfiguration;
import android.car.cluster.renderer.InstrumentClusterRenderer;
import android.car.cluster.renderer.MediaRenderer;
import android.car.cluster.renderer.NavigationRenderer;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.view.View;

/**
 * Demo implementation of {@code InstrumentClusterRenderer}.
 */
public class DemoInstrumentClusterRenderer extends InstrumentClusterRenderer {

    private DemoInstrumentClusterView mView;
    private Context mContext;
    private CallStateMonitor mPhoneStatusMonitor;
    private DemoPhoneRenderer mPhoneRenderer;

    @Override
    public void onCreate(Context context) {
        mContext = context;
    }

    @Override
    public View onCreateView(DisplayConfiguration displayConfiguration) {
        mView = new DemoInstrumentClusterView(mContext);
        mPhoneRenderer = new DemoPhoneRenderer(mView);
        return mView;
    }

    @Override
    public void onStart() {
        mPhoneStatusMonitor = new CallStateMonitor(mContext, mPhoneRenderer);
    }

    @Override
    public void onStop() {
        mPhoneStatusMonitor.release();
    }

    @Override
    protected NavigationRenderer createNavigationRenderer() {
        return new DemoNavigationRenderer(mView);
    }

    @Override
    protected MediaRenderer createMediaRenderer() {
        return new DemoMediaRenderer(mView);
    }

    @Override
    public CarNavigationInstrumentCluster getNavigationProperties() {
        // TODO
        return null;
    }
}
