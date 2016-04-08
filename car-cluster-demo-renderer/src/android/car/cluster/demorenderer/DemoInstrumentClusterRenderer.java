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

import android.app.Presentation;
import android.car.cluster.renderer.InstrumentClusterRenderer;
import android.car.cluster.renderer.NavigationRenderer;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * Demo implementation of {@code InstrumentClusterRenderer}.
 */
public class DemoInstrumentClusterRenderer extends InstrumentClusterRenderer {

    private final static String TAG = DemoInstrumentClusterRenderer.class.getSimpleName();

    private DemoInstrumentClusterView mView;
    private Context mContext;
    private CallStateMonitor mPhoneStatusMonitor;
    private MediaStateMonitor mMediaStateMonitor;
    private DemoPhoneRenderer mPhoneRenderer;
    private DemoMediaRenderer mMediaRenderer;
    private Presentation mPresentation;

    @Override
    public void onCreate(Context context) {
        mContext = context;

        final Display display = getInstrumentClusterDisplay(context);

        if (display != null) {
            runOnMainThread(() -> {
                Log.d(TAG, "Initializing renderer in main thread.");
                try {
                    mPresentation = new InstrumentClusterPresentation(mContext, display);

                    ViewGroup rootView = (ViewGroup) LayoutInflater.from(mContext).inflate(
                            R.layout.instrument_cluster, null);

                    mPresentation.setContentView(rootView);
                    View rendererView = createView();
                    rootView.addView(rendererView);
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                    throw e;
                }
            });
        }
    }

    private View createView() {
        mView = new DemoInstrumentClusterView(mContext);
        mPhoneRenderer = new DemoPhoneRenderer(mView);
        mMediaRenderer = new DemoMediaRenderer(mView);
        return mView;
    }

    @Override
    public void onStart() {
        runOnMainThread(() -> {
            Log.d(TAG, "onStart");
            mPhoneStatusMonitor = new CallStateMonitor(mContext, mPhoneRenderer);
            mMediaStateMonitor = new MediaStateMonitor(mContext, mMediaRenderer);
            mPresentation.show();
        });
    }

    @Override
    public void onStop() {
        runOnMainThread(() -> {
            if (mPhoneStatusMonitor != null) {
                mPhoneStatusMonitor.release();
                mPhoneStatusMonitor = null;
            }

            if (mMediaStateMonitor != null) {
                mMediaStateMonitor.release();
                mMediaStateMonitor = null;
            }
            mPhoneRenderer = null;
            mMediaRenderer = null;
        });
    }

    @Override
    protected NavigationRenderer createNavigationRenderer() {
        return ThreadSafeNavigationRenderer.createFor(
                Looper.getMainLooper(),
                new DemoNavigationRenderer(mView));
    }

    private static Display getInstrumentClusterDisplay(Context context) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        Display[] displays = displayManager.getDisplays();

        Log.d(TAG, "There are currently " + displays.length + " displays connected.");
        for (Display display : displays) {
            Log.d(TAG, "  " + display);
        }

        if (displays.length > 1) {
            // TODO: assuming that secondary display is instrument cluster. Put this into settings?
            return displays[1];
        }
        return null;
    }

    private static void runOnMainThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
}
