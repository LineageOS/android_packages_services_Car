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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.HardwareBuffer;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CarEvsCameraPreviewActivity extends Activity {
    private static final String TAG = CarEvsCameraPreviewActivity.class.getSimpleName();
    private static final int WAIT_FOR_SURFACE_READY_IN_MS = 100;
    private static final int INITIAL_COLOR_SATURATION_LEVEL = 50;

    /** Callback executor */
    private final ExecutorService mCallbackExecutor = Executors.newFixedThreadPool(1);

    /** Target surface ready signal */
    private final CountDownLatch mSurfaceReadySignal = new CountDownLatch(1);

    /** Queue containing received frame buffers */
    private final ArrayList<CarEvsBufferDescriptor> mBufferQueue = new ArrayList<>();

    private final Object mLock = new Object();

    /** Display manager to monitor the display's state */
    private DisplayManager mDisplayManager;

    /** Surface the camera to be drawn */
    private Surface mPreviewSurface;

    /** The background thread that draws the camera preview */
    private HandlerThread mBackgroundThread;

    /** The handler for the above background thread. */
    private Handler mBackgroundHandler;

    /** Color saturation adjustment */
    private int mSaturation;

    /** Current display state */
    private int mDisplayState = Display.STATE_OFF;

    /** Tells whether or not a video stream is running */
    private boolean mStreamRunning = false;

    /** True if we need to start a video stream */
    private boolean mActivityResumed = false;

    private Car mCar;
    private CarEvsManager mEvsManager;
    private TextureView mTextureView;

    private final TextureView.SurfaceTextureListener mTextureListener =
            new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(
                        SurfaceTexture surfaceTexture, int width, int height) {

                    mPreviewSurface = new Surface(surfaceTexture);
                    mSurfaceReadySignal.countDown();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    // If returns false, the client needs to call SurfaceTexture.release().
                    // Most applications should return true.
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            };

    // The Activity with showWhenLocked doesn't go to sleep even if the display sleeps.
    // So we'd like to monitor the display state and react on it manually.
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

    /** Callback to listen to EVS stream */
    private final CarEvsManager.CarEvsStreamCallback mStreamHandler =
            new CarEvsManager.CarEvsStreamCallback() {

        @Override
        public void onStreamEvent(int event) {
            Log.i(TAG, "Received: " + event);
        }

        @Override
        public void onNewFrame(CarEvsBufferDescriptor buffer) {
            // Enqueues a new frame and posts a rendering job
            mBufferQueue.add(buffer);
            mBackgroundHandler.post(() -> { renderPreview(); });
        }
    };

    private final CarServiceLifecycleListener mCarServiceLifecycleListener = (car, ready) -> {
        if (!ready) {
            Log.d(TAG, "Disconnected from the Car Service");
            // Upon the CarService's accidental termination, CarEvsService gets released and
            // CarEvsManager deregisters all listeners and callbacks.  So, we simply release
            // CarEvsManager instance and update the status in handleVideoStreamLocked().
            synchronized (mLock) {
                mEvsManager = null;
                handleVideoStreamLocked();
            }
        } else {
            Log.d(TAG, "Connected to the Car Service");
            try {
                synchronized (mLock) {
                    mEvsManager = (CarEvsManager) car.getCarManager(Car.CAR_EVS_SERVICE);
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

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.evs_preview_activity);
        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        mSaturation = INITIAL_COLOR_SATURATION_LEVEL;
        SeekBar saturationBar = findViewById(R.id.saturationAdjustBar);
        saturationBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar saturationBar, int progresValue, boolean fromUser) {
                        mSaturation = progresValue;
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar saturationBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar saturationBar) {}
                });

        mTextureView = findViewById(R.id.texture);
        mTextureView.setSurfaceTextureListener(mTextureListener);

        mCar = Car.createCar(getApplicationContext(), /* handler = */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, mCarServiceLifecycleListener);
    }

    /** Starts a background thread and its {@link Handler} */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread(CarEvsCameraPreviewActivity.class.getSimpleName());
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /** Stops a background thread and its {@link Handler} */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        startBackgroundThread();
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
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
        stopBackgroundThread();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mCar.disconnect();
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
    }

    private void handleVideoStreamLocked() {
        if (mActivityResumed && !mStreamRunning && mEvsManager != null &&
                mDisplayState == Display.STATE_ON) {
            // TODO(b/179517136): Acquires a token from Intent and passes it with below request.
            mEvsManager.startVideoStream(CarEvsManager.SERVICE_TYPE_REARVIEW,
                    /* token = */ null, mCallbackExecutor, mStreamHandler);
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

    /** Draws a camera preview on the surface in BackgroundThread. */
    private void renderPreview() {
        if (mBufferQueue.isEmpty()) {
            Log.i(TAG, "No new frame to draw");
            return;
        }

        // Retrieves the oldest frame in the queue
        CarEvsBufferDescriptor newFrame = mBufferQueue.get(0);
        HardwareBuffer bufferToRender = newFrame.getHardwareBuffer();
        mBufferQueue.remove(0);

        // Ensures a target surface is ready
        try {
            if (!mSurfaceReadySignal.await(WAIT_FOR_SURFACE_READY_IN_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Target Surface is not ready yet.");
                return;
            }

            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(((float)mSaturation / 100));
            Paint greyscalePaint = new Paint();
            greyscalePaint.setColorFilter(new ColorMatrixColorFilter(cm));

            // Enlarges the preview frame to fill the entire screen
            Point displayBottomRight = new Point();
            getWindowManager().getDefaultDisplay().getRealSize(displayBottomRight);
            Matrix matrix = new Matrix();
            matrix.setScale((float)displayBottomRight.x / bufferToRender.getWidth(),
                            (float)displayBottomRight.y / bufferToRender.getHeight());

            // As we're going to render the preview with a sofrware Canvas, a software bitmap
            // is being generated by copying data from a new frame to be drawn.  This explicit
            // data copy may decimate the performance and therefore prefer to be replaced with
            // another way to render a hardwarebuffer's contents directly.
            Bitmap previewBitmap = Bitmap.wrapHardwareBuffer(bufferToRender, null)
                    .copy(Bitmap.Config.ARGB_8888, false);

            Canvas canvas = mPreviewSurface.lockCanvas(null);
            canvas.drawBitmap(previewBitmap, matrix, greyscalePaint);
            mPreviewSurface.unlockCanvasAndPost(canvas);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            // Returns the buffer
            mEvsManager.returnFrameBuffer(newFrame);
        }
    }
}
