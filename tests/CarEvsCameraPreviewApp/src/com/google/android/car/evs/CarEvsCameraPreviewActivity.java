/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.car.evs;

import static android.hardware.display.DisplayManager.DisplayListener;

import android.app.Activity;
import android.car.Car;
import android.car.Car.CarServiceLifecycleListener;
import android.car.CarNotConnectedException;
import android.car.evs.CarEvsBufferDescriptor;
import android.car.evs.CarEvsManager;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.View;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CarEvsCameraPreviewActivity extends Activity {
    private static final String TAG = CarEvsCameraPreviewActivity.class.getSimpleName();

    /** Buffer queue to store references of received frames */
    private final ArrayList<CarEvsBufferDescriptor> mBufferQueue = new ArrayList<>();

    private final Object mLock = new Object();

    /** Callback executors */
    private final ExecutorService mCallbackExecutor = Executors.newFixedThreadPool(1);

    /** GL backed surface view to render the camera preview */
    private CarEvsCameraGLSurfaceView mView;

    /** Display manager to monitor the display's state */
    private DisplayManager mDisplayManager;

    /** Current display state */
    private int mDisplayState = Display.STATE_OFF;

    /** Tells whether or not a video stream is running */
    private boolean mStreamRunning = false;

    /** True if we need to start a video stream */
    private boolean mActivityResumed = false;

    private Car mCar;
    private CarEvsManager mEvsManager;

    private IBinder mSessiontoken;

    /** Callback to listen to EVS stream */
    private final CarEvsManager.CarEvsStreamCallback mStreamHandler =
            new CarEvsManager.CarEvsStreamCallback() {

        @Override
        public void onStreamEvent(int event) {
            // This reference implementation only monitors a stream event without any action.
            Log.i(TAG, "Received: " + event);
        }

        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            // Enqueues a new frame and posts a rendering job
            synchronized (mBufferQueue) {
                mBufferQueue.add(buffer);
            }
        }
    };

    /** CarEvsService status change listener */
    private final CarEvsManager.CarEvsStatusListener mStatusListener = (status) -> {
        if (status.getServiceType() != CarEvsManager.SERVICE_TYPE_REARVIEW) {
            Log.e(TAG, "Unexpected service type: " + status.getServiceType());
            return;
        }

        switch (status.getState()) {
            case CarEvsManager.SERVICE_STATE_REQUESTED:
                // Upon this state transition, we request to start a video stream
                // from the rearview service.
                // TODO(b/179517136): Acquires a token from Intent and passes it with below request.
                synchronized (mLock) {
                    handleVideoStreamLocked();
                }
                break;

            case CarEvsManager.SERVICE_STATE_ACTIVE:
                Log.d(TAG, "Video stream started, type = " + status.getServiceType());
                break;

            case CarEvsManager.SERVICE_STATE_UNAVAILABLE:
                // CarEvsManager lost a native EVS service.
                Log.d(TAG, "We've lost a connection to the EVS service.");

                // Drop all buffer references safely
                synchronized (mBufferQueue) {
                    mBufferQueue.clear();
                }

                synchronized (mLock) {
                    if (!mStreamRunning) {
                        // Exits if the preview is not running.
                        finish();
                    }
                }
                break;

            case CarEvsManager.SERVICE_STATE_INACTIVE:
                synchronized (mLock) {
                    if (mStreamRunning) {
                        // We lost the service while the preview was running so start a video stream
                        // upon the service restore.
                        mStreamRunning = false;
                        handleVideoStreamLocked();
                    }
                }
                break;

            default:
                Log.d(TAG, "No actions on a status changed event, type = " +
                        status.getServiceType() + " state = " + status.getState());
                break;
        }
    };

    /**
     * The Activity with showWhenLocked doesn't go to sleep even if the display sleeps.
     * So we'd like to monitor the display state and react on it manually.
     */
    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            int state = decideViewVisibility();
            synchronized (mLock) {
                mDisplayState = state;
                handleVideoStreamLocked();
            }
        }
    };

    /** CarService status listener  */
    private final CarServiceLifecycleListener mCarServiceLifecycleListener = (car, ready) -> {
        if (!ready) {
            Log.d(TAG, "Disconnected from the Car Service");
            // Upon the CarService's accidental termination, CarEvsService gets released and
            // CarEvsManager deregisters all listeners and callbacks.  So, we simply release
            // CarEvsManager instance and update the status in handleVideoStreamLocked().
            synchronized (mLock) {
                mCar = null;
                mEvsManager = null;
                handleVideoStreamLocked();
            }
        } else {
            Log.d(TAG, "Connected to the Car Service");
            try {
                synchronized (mLock) {
                    mCar = car;
                    mEvsManager = (CarEvsManager) car.getCarManager(Car.CAR_EVS_SERVICE);
                    mEvsManager.setStatusListener(mCallbackExecutor, mStatusListener);
                    handleVideoStreamLocked();
                }
            } catch (CarNotConnectedException err) {
                Log.e(TAG, "Failed to connect to the Car Service");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        int state = decideViewVisibility();
        synchronized (mLock) {
            mDisplayState = state;
        }

        Car.createCar(getApplicationContext(), /* handler = */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mCarServiceLifecycleListener);

        mView = new CarEvsCameraGLSurfaceView(getApplication(), this);
        setContentView(mView);

        setSessionToken(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setSessionToken(intent);
    }

    private void setSessionToken(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            mSessiontoken = null;
            return;
        }
        mSessiontoken = extras.getBinder(CarEvsManager.EXTRA_SESSION_TOKEN);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();

        synchronized (mLock) {
            mActivityResumed = true;
            handleVideoStreamLocked();
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();

        synchronized (mLock) {
            mActivityResumed = false;
            handleVideoStreamLocked();
        }

        synchronized (mBufferQueue) {
            mBufferQueue.clear();
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();

        // Request to stop current service and unregister a status listener
        synchronized (mLock) {
            if (mEvsManager != null) {
                mEvsManager.stopActivity();
                mEvsManager.clearStatusListener();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        synchronized (mLock) {
            if (mCar != null) {
                mCar.disconnect();
            }
        }
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
    }

    private void handleVideoStreamLocked() {
        if (mActivityResumed && !mStreamRunning && mEvsManager != null &&
                mDisplayState == Display.STATE_ON) {
            mEvsManager.startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                    mSessiontoken, mCallbackExecutor, mStreamHandler);
            mStreamRunning = true;
        } else if (mStreamRunning) {
            // Stops a video stream if it's active.
            if (mEvsManager != null) {
                mEvsManager.stopVideoStream();
            }
            mStreamRunning = false;
        }
    }

    // Hides the view when the display is off to save the system resource, since this has
    // 'showWhenLocked' attribute, this will not go to PAUSED state even if the display turns off.
    private int decideViewVisibility() {
        Display defaultDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int state = defaultDisplay.getState();
        Log.d(TAG, "decideShowWhenLocked: displayState=" + state);
        if (state == Display.STATE_ON) {
            getWindow().getDecorView().setVisibility(View.VISIBLE);
        } else {
            getWindow().getDecorView().setVisibility(View.INVISIBLE);
        }

        return state;
    }

    /** Get a new frame */
    public CarEvsBufferDescriptor getNewFrame() {
        synchronized (mBufferQueue) {
            if (mBufferQueue.isEmpty()) {
                return null;
            }

            // The renderer refreshes faster than 30fps so it's okay to fetch the frame from the
            // front of the buffer queue always.
            CarEvsBufferDescriptor newFrame = mBufferQueue.get(0);
            mBufferQueue.remove(0);

            return newFrame;
        }
    }

    /** Request to return a buffer we're done with */
    public void returnBuffer(CarEvsBufferDescriptor buffer) {
        mEvsManager.returnFrameBuffer(buffer);
    }
}
