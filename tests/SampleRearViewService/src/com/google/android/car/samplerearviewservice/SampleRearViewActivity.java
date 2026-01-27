/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.google.android.car.samplerearviewservice;

import android.app.Activity;
import android.car.feature.Flags;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class SampleRearViewActivity extends Activity {
    public static final String ACTION_STOP_REARVIEW = "STOP_REARVIEW";
    private static final String TAG = "SampleRearViewActivity";
    private CameraManager mCameraManager;
    private SurfaceView mSurfaceView;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CameraDevice mCameraDevice;
    private String mCameraId;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private CameraCaptureSession mCaptureSession;
    private CameraSessionListener mCameraSessionListener;
    private CameraStateListener mCameraStateListener;
    private BroadcastReceiver mBroadcastReceiver;
    private View mRootView;
    private static final long RECREATE_DELAY_MS = 100;

    private DisplayManager mDisplayManager;
    private boolean mPreviewRunning = false;
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == getDisplayId()) {
                        int state = getDisplay().getState();
                        if (state == Display.STATE_ON) {
                            startPreview();
                        } else {
                            stopPreview();
                        }
                    }
                }
            };

    private void startPreview() {
        if (mCaptureSession == null || mCaptureRequestBuilder == null) {
            Log.e(
                    TAG,
                    "Unable to start the preview without a CaptureSession or"
                            + " CaptureRequestBuilder");
            return;
        }
        if (mPreviewRunning) {
            Log.w(TAG, "Trying to start already running preview.");
            return;
        }
        try {
            mCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mHandler);
            mPreviewRunning = true;
            Log.d(TAG, "Preview started");
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start camera preview.", e);
        }
    }

    private void stopPreview() {
        if (mCaptureSession == null) {
            Log.w(TAG, "Trying to stop the preview without a CaptureSession");
            return;
        }
        if (!mPreviewRunning) {
            Log.w(TAG, "Trying to stop the preview that is not running.");
            return;
        }
        try {
            mCaptureSession.stopRepeating();
            mPreviewRunning = false;
            Log.d(TAG, "Preview stopped");
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to stop camera preview.", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Flags.camera2SampleRearViewService()) {
            Log.w(TAG, "Feature camera2_sample_rear_view_service is disabled, finishing activity.");
            finish();
            return;
        }

        // This flag is required to display the camera feed on top of the lockscreen.
        setShowWhenLocked(true);

        // Inflate the layout that contains the SurfaceView for the camera preview.
        LayoutInflater inflater = getSystemService(LayoutInflater.class);
        mRootView = inflater.inflate(R.layout.rear_view_activity, null);

        int width = getResources().getDimensionPixelOffset(R.dimen.camera_preview_width);
        int height = getResources().getDimensionPixelOffset(R.dimen.camera_preview_height);

        // In order for this activity to be displayed as a system overlay, on top of all other
        // applications and UI elements, we are adding it directly to the WindowManager.
        // The LayoutParams below define the behavior of this overlay.
        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width, height,
                        // TYPE_VOLUME_OVERLAY is a high-level window type that ensures this view
                        // is displayed on top of most other UI elements, including the navigation
                        // bar. This requires INTERNAL_SYSTEM_WINDOW permission.
                        WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY,
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.dimAmount = getResources().getFloat(R.dimen.config_cameraBackgroundScrim);

        WindowManager wm = getSystemService(WindowManager.class);
        wm.addView(mRootView, params);

        mCameraManager = getSystemService(CameraManager.class);
        mSurfaceView = mRootView.findViewById(R.id.rear_view_camera_preview);
        SurfacePreviewListener surfacePreviewListener = new SurfacePreviewListener();
        mSurfaceView.getHolder().addCallback(surfacePreviewListener);
        mHandlerThread = new HandlerThread("CameraPreviewThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        mCameraStateListener = new CameraStateListener();
        mCameraSessionListener = new CameraSessionListener();

        mBroadcastReceiver =
                new BroadcastReceiver() {
                    /**
                     * ActivityManagerService encodes the reason for a request to close system
                     * dialogs with this key.
                     */
                    private static final String EXTRA_DIALOG_CLOSE_REASON = "reason";

                    /**
                     * This string literal is from
                     * com.android.systemui.car.systembar.CarSystemBarButton class.
                     */
                    private static final String DIALOG_CLOSE_REASON_CAR_SYSTEMBAR_BUTTON =
                            "carsystembarbutton";

                    /**
                     * This string literal is from com.android.server.policy.PhoneWindowManager
                     * class.
                     */
                    private static final String DIALOG_CLOSE_REASON_HOME_KEY = "homekey";

                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (ACTION_STOP_REARVIEW.equals(action)) {
                            finish();
                        } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                            Bundle extras = intent.getExtras();
                            if (extras != null) {
                                String reason = extras.getString(EXTRA_DIALOG_CLOSE_REASON);
                                if (!DIALOG_CLOSE_REASON_CAR_SYSTEMBAR_BUTTON.equals(reason)
                                        && !DIALOG_CLOSE_REASON_HOME_KEY.equals(reason)) {
                                    Log.i(
                                            TAG,
                                            "Ignore a request to close the system dialog with a"
                                                    + " reason = "
                                                    + reason);
                                    return;
                                }
                                Log.d(TAG, "Requested to close the dialog, reason = " + reason);
                            }
                            finish();
                        } else {
                            Log.e(TAG, "Unexpected intent " + intent);
                        }
                    }
                };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STOP_REARVIEW);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        // Because this service runs as the system user (user 0), it needs to be able
        // to receive broadcasts from all users to know when to close (e.g. when a
        // system dialog appears).
        registerReceiverForAllUsers(
                mBroadcastReceiver, filter, null, null, Context.RECEIVER_EXPORTED);
    }

    /**
     * Opens the rear-view camera.
     *
     * <p>This method iterates through the available cameras on the device and selects the one that
     * is designated as the rear-view camera by checking its lens facing and automotive location
     * characteristics.
     */
    public void openCamera() {
        String[] cameraIdList = null;
        try {
            cameraIdList = mCameraManager.getCameraIdList();
            for (String cameraId : cameraIdList) {
                Log.d(TAG, "Found  camera with ID: " + cameraId);
                CameraCharacteristics characteristics =
                        mCameraManager.getCameraCharacteristics(cameraId);
                int[] cameraLensFacing =
                        characteristics.get(CameraCharacteristics.AUTOMOTIVE_LENS_FACING);
                Integer cameraLocation =
                        characteristics.get(CameraCharacteristics.AUTOMOTIVE_LOCATION);

                boolean isAutomotiveRearFacing = false;
                if (cameraLensFacing != null) {
                    for (int facing : cameraLensFacing) {
                        if (facing == CameraCharacteristics.AUTOMOTIVE_LENS_FACING_EXTERIOR_REAR) {
                            isAutomotiveRearFacing = true;
                            break;
                        }
                    }
                }

                boolean isRearLocation = false;
                if (cameraLocation != null) {
                    isRearLocation =
                            (cameraLocation
                                    == CameraCharacteristics.AUTOMOTIVE_LOCATION_EXTERIOR_REAR);
                }

                if (isAutomotiveRearFacing && isRearLocation) {
                    mCameraId = cameraId;
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding or opening camera", e);
        }

        if (mCameraId == null) {
            // Retry only if the camera service reported no cameras at all,
            // suggesting it might still be initializing.
            if (cameraIdList == null || cameraIdList.length == 0) {
                Log.w(TAG, "No cameras detected. Retrying in case service is not ready.");
                mHandler.postDelayed(() -> runOnUiThread(() -> recreate()), RECREATE_DELAY_MS);
            } else {
                // If there are cameras but none match, it's a permanent failure. Abort.
                Log.e(TAG, "Cameras are present, but no EXTERIOR_REAR camera found. Aborting.");
                finish();
            }
        } else {
            try {
                mCameraManager.openCamera(mCameraId, mCameraStateListener, mHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "Failed to open camera.", e);
            }
        }
    }

    /**
     * A {@link SurfaceHolder.Callback} that handles the lifecycle events of the {@link
     * SurfaceView}.
     *
     * <p>This listener is responsible for opening the camera when the surface is created and
     * handling its destruction.
     */
    public class SurfacePreviewListener implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "Surface created");
            openCamera();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "Surface Changed to: " + width + "x" + height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "Surface destroyed");
        }
    }

    public static class HandlerExecutor implements Executor {
        private final Handler mHandler;

        public HandlerExecutor(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void execute(Runnable runCmd) {
            mHandler.post(runCmd);
        }
    }

    /**
     * A CameraDevice.StateCallback that handles the state changes of the camera device.
     *
     * <p>This listener is responsible for creating the camera capture session when the camera is
     * opened and handling disconnection and error scenarios by attempting to reconnect.
     */
    class CameraStateListener extends CameraDevice.StateCallback {

        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            try {
                mCaptureRequestBuilder =
                        mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mCaptureRequestBuilder.addTarget(mSurfaceView.getHolder().getSurface());
                List<OutputConfiguration> outputs = new ArrayList<>();
                outputs.add(new OutputConfiguration(mSurfaceView.getHolder().getSurface()));
                SessionConfiguration sessionConfig =
                        new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputs,
                                new HandlerExecutor(mHandler),
                                mCameraSessionListener);
                CaptureRequest request = mCaptureRequestBuilder.build();
                sessionConfig.setSessionParameters(request);
                mCameraDevice.createCaptureSession(sessionConfig);

            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to start capture session. Got CameraAccessException.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to start capture session. Got IllegalStateException.");
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "Camera disconnected, restarting activity to reestablish connection");
            mPreviewRunning = false;
            mCameraDevice = null;
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            camera.close();
            mHandler.postDelayed(() -> runOnUiThread(() -> recreate()), RECREATE_DELAY_MS);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.w(TAG, "Camera error " + error + ", restarting activity to reestablish connection");
            camera.close();
            mHandler.postDelayed(() -> runOnUiThread(() -> recreate()), RECREATE_DELAY_MS);
        }

        @Override
        public void onClosed(CameraDevice camera) {
            mPreviewRunning = false;
        }
    }

    /**
     * A {@link CameraCaptureSession.StateCallback} that handles the state changes of the camera
     * capture session.
     *
     * <p>This listener is responsible for starting the camera preview once the capture session is
     * successfully configured.
     */
    class CameraSessionListener extends CameraCaptureSession.StateCallback {

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mCaptureSession = session;
            startPreview();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Camera capture session configuration failed");
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            Log.i(TAG, "Camera capture session closed");
            mPreviewRunning = false;
        }
    }

    /**
     * Cleans up all the resources used by the activity.
     *
     * <p>This method is called when the activity is being destroyed and is responsible for
     * releasing the camera, capture session, handler thread, and broadcast receiver to prevent
     * memory leaks.
     */
    @Override
    protected void onDestroy() {
        try {
            // Stop the repeating request and close the capture session.
            // A CameraAccessException can be thrown if the camera is already disconnected or
            // has encountered an error, which is an expected scenario when the camera server dies.
            if (mCaptureSession != null) {
                stopPreview();
                mCaptureSession.close();
                mCaptureSession = null;
            }

            // Safely quit the handler thread.
            if (mHandlerThread != null) {
                mHandlerThread.quitSafely();
                mHandlerThread.join();
            }

            mHandler = null;
            mCameraSessionListener = null;

            // Close the camera device.
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            // Unregister the broadcast receiver.
            if (mBroadcastReceiver != null) {
                unregisterReceiver(mBroadcastReceiver);
            }

            if (mDisplayManager != null) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
            }

            // Remove the view from the window manager.
            if (mRootView != null) {
                WindowManager wm = getSystemService(WindowManager.class);
                wm.removeView(mRootView);
            }

            super.onDestroy();
        } catch (Exception e) {
            // This can happen if the thread is interrupted while joining.
            Log.e(TAG, "Error during onDestroy cleanup", e);
        }
    }
}
