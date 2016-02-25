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

import android.car.cluster.InstrumentClusterRenderer;
import android.car.cluster.NavigationRenderer;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demo implementation of {@code InstrumentClusterRenderer}.
 */
public class DemoInstrumentClusterRenderer extends InstrumentClusterRenderer {
    private final static String TAG = DemoInstrumentClusterRenderer.class.getSimpleName();

    private static int TIMEOUT_MS = 5000;

    private DemoInstrumentClusterView mView;
    private DemoPresentation mPresentation;
    private CountDownLatch mPresentationCreatedLatch = new CountDownLatch(1);
    private Looper mUiLooper;

    @Override
    public void onCreate(final Context context, final Display display) {
        new Thread() {
            @Override
            public void run() {
                Log.d(TAG, "UI thread started.");
                Looper.prepare();
                mPresentation = new DemoPresentation(context, display);
                mView = new DemoInstrumentClusterView(mPresentation.getContext());
                mPresentation.setContentView(mView);
                mPresentationCreatedLatch.countDown();
                mUiLooper = Looper.myLooper();
                Looper.loop();
            }
        }.start();
    }

    private boolean waitForPresentation() {
        try {
            boolean ready = mPresentationCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ready) {
                Log.w(TAG, "Presentation was not created within " + TIMEOUT_MS + "ms.",
                        new RuntimeException() /* for stack trace */);
            }
            return ready;
        } catch (InterruptedException e) {
            Log.e(TAG, "Presentation creation interrupted.", e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onStart() {
        if (!waitForPresentation()) {
            return;
        }

        mPresentation.show();
    }

    @Override
    public void onStop() {
        if (!waitForPresentation()) {
            return;
        }

        mPresentation.dismiss();
    }

    @Override
    protected NavigationRenderer createNavigationRenderer() {
        if (!waitForPresentation()) {
            return null;
        }

        return new DemoNavigationRenderer(mView, mUiLooper);
    }

    @Override
    public CarNavigationInstrumentCluster getNavigationProperties() {
        // TODO
        return null;
    }
}
