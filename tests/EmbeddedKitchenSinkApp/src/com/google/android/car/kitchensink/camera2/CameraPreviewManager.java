/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.car.kitchensink.camera2;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

final class CameraPreviewManager {
    private static final String TAG = CameraPreviewManager.class.getSimpleName();
    private static final int MSG_SURFACE_READY = 1;
    private static final int MSG_CAMERA_OPENED = 2;
    private static final int MSG_SESSION_REQUESTED = 3;
    private final CameraSessionListener mCameraSessionListener;
    private final CameraDeviceStateListener mCameraDeviceStateListener;
    private final String mCameraId;
    private final SurfaceView mSurfaceView;
    private final CameraManager mCameraManager;
    private final Handler mSessionHandler;
    private final Handler mConfigHandler;
    private boolean mIsCameraConnected;
    private boolean mIsSurfaceCreated;
    private boolean mIsCaptureSessionRequested;
    private boolean mIsCaptureSessionCreated;
    private CameraDevice mCameraDevice;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mCaptureSession;

    /**
     * CameraPreviewManager
     * Class designed to create and manage a camera preview for a single camera device.
     *
     * @param cameraId Specifies for which camera this instance is managing the preview
     * @param surfaceView The SurfaceView on which the preview will be shown
     * @param cameraManager The system camera manager
     */
    CameraPreviewManager(
            String cameraId, SurfaceView surfaceView,
            CameraManager cameraManager, HandlerThread sessionHandlerThread) {
        mCameraId = cameraId;
        mSurfaceView = surfaceView;
        mCameraManager = cameraManager;
        mSurfaceView.getHolder().addCallback(new SurfacePreviewListener());

        mCameraDeviceStateListener = new CameraDeviceStateListener();
        mCameraSessionListener = new CameraSessionListener();

        mSessionHandler = new Handler(sessionHandlerThread.getLooper());
        mConfigHandler = new Handler(Looper.getMainLooper(), new CameraSurfaceInitListener());
    }

    void openCamera() {
        try {
            Log.d(TAG, "Opening camera " + mCameraId);
            mCameraManager.openCamera(mCameraId, mCameraDeviceStateListener, null);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to open camera " + mCameraId + ". Got:", e);
        }
    }

    void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
            mIsCameraConnected = false;
            Log.d(TAG, "Closed camera " + mCameraId);
        }
    }

    void startSession() {
        mIsCaptureSessionRequested = true;
        mConfigHandler.sendEmptyMessage(MSG_SESSION_REQUESTED);
    }

    void stopSession() {
        Log.d(TAG, "Attempting to stop current session of camera " + mCameraId);
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
                mCaptureSession = null;
            }
            Log.d(TAG, "Stopped capture session " + mCameraId);
        } catch (Exception e) {
            Log.e(TAG, "Exception caught while stopping camera session " + mCameraId, e);
        }
        mIsCaptureSessionRequested = false;
        mIsCaptureSessionCreated = false;
    }

    final class SurfacePreviewListener implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "Surface created");
            mIsSurfaceCreated = true;
            mConfigHandler.sendEmptyMessage(MSG_SURFACE_READY);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "Surface Changed to: " + width + "x" + height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "Surface destroyed");
            mIsSurfaceCreated = false;
        }
    }

    static final class SessionExecutor implements Executor {
        private final Handler mExecutorHandler;

        SessionExecutor(Handler handler) {
            mExecutorHandler = handler;
        }

        @Override
        public void execute(Runnable runCmd) {
            mExecutorHandler.post(runCmd);
        }
    }

    final class CameraDeviceStateListener extends CameraDevice.StateCallback {

        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "Camera Opened");
            mCameraDevice = camera;
            mIsCameraConnected = true;
            mConfigHandler.sendEmptyMessage(MSG_CAMERA_OPENED);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mIsCameraConnected = false;
        }

        @Override
        public void onClosed(CameraDevice camera) {
            mIsCameraConnected = false;
        }

        @Override
        public void onError(CameraDevice camera, int error) {}
    }

    final class CameraSurfaceInitListener implements Handler.Callback {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch(msg.what) {
                case MSG_CAMERA_OPENED:
                case MSG_SURFACE_READY:
                case MSG_SESSION_REQUESTED:
                    if ((mCameraDevice != null)
                            && mIsCameraConnected
                            && mIsSurfaceCreated
                            && mIsCaptureSessionRequested
                            && !mIsCaptureSessionCreated) {
                        Log.d(TAG, "All conditions satisfied, starting session.");
                        createCaptureSession();
                    }
            }
            return true;
        }
    }

    private void createCaptureSession() {
        try {
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(mSurfaceView.getHolder().getSurface());
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mSurfaceView.getHolder().getSurface()));
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs,
                    new SessionExecutor(mSessionHandler), mCameraSessionListener);
            mCaptureRequest = captureRequestBuilder.build();
            sessionConfig.setSessionParameters(mCaptureRequest);
            mCameraDevice.createCaptureSession(sessionConfig);
            mIsCaptureSessionCreated = true;
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to create capture session.", e);
        }
    }

    final class CameraSessionListener extends CameraCaptureSession.StateCallback {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            mCaptureSession = session;
            try {
                mCaptureSession.setRepeatingRequest(
                        mCaptureRequest, /* listener= */ null, mSessionHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "Failed to start camera preview.", e);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Failed to configure capture session.");
        }
    }
}
