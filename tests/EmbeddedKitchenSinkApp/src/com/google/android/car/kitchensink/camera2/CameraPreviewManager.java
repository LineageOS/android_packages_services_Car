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
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

final class CameraPreviewManager {
    private static final String TAG = CameraPreviewManager.class.getSimpleName();
    private static final int MSG_SURFACE_READY = 1;
    private static final int MSG_CAMERA_OPENED = 2;
    private static final int MSG_SESSION_REQUESTED = 3;
    private static final Size VIDEO_SIZE = new Size(/* width= */ 1280, /* height= */ 720);
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_BIT_RATE = 10_000_000;
    private final SessionStateListener mSessionStateListener;
    private final CameraDeviceStateListener mCameraDeviceStateListener;
    private final SessionCaptureListener mSessionCaptureListener;
    private final String mCameraId;
    private final SurfaceView mSurfaceView;
    private final CameraManager mCameraManager;
    private final Handler mSessionHandler;
    private final Handler mConfigHandler;
    private boolean mIsCameraConnected;
    private boolean mIsSurfaceCreated;
    private boolean mIsPreviewSessionRequested;
    private boolean mIsPreviewSessionCreated;
    private boolean mIsRecordingSessionRequested;
    private boolean mIsRecordingSessionCreated;
    private CameraDevice mCameraDevice;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mCaptureSession;
    private MediaRecorder mRecorder;
    private String mVideoFilePath;

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
        mSessionStateListener = new SessionStateListener();
        mSessionCaptureListener = new SessionCaptureListener();

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
        Log.d(TAG, "Initialize MediaRecorder.");
        mRecorder = new MediaRecorder();
    }

    void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
            mIsCameraConnected = false;
            Log.d(TAG, "Closed camera " + mCameraId);
        }
        Log.d(TAG, "Release MediaRecorder.");
        mRecorder.reset();
        mRecorder.release();
    }

    void startPreviewSession() {
        mIsPreviewSessionRequested = true;
        mConfigHandler.sendEmptyMessage(MSG_SESSION_REQUESTED);
    }

    void startRecordingSession(String filePrefix) {
        mIsRecordingSessionRequested = true;
        mVideoFilePath = String.format("%s_id_%s.mp4", filePrefix, mCameraId);
        Log.d(TAG, String.format(
                "Video recording path for camera %s set to %s", mCameraId, mVideoFilePath));
        setupMediaRecorder();
        mConfigHandler.sendEmptyMessage(MSG_SESSION_REQUESTED);
    }

    private void setupMediaRecorder() {
        mRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mRecorder.setVideoSize(VIDEO_SIZE.getWidth(), VIDEO_SIZE.getHeight());
        mRecorder.setVideoFrameRate(VIDEO_FRAME_RATE);
        mRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE);
        mRecorder.setOutputFile(mVideoFilePath);
        try {
            mRecorder.prepare();
        } catch (IllegalStateException | IOException e) {
            Log.e(TAG, "Unable to prepare media recorder with camera " + mCameraId, e);
        }
    }

    void stopSession() {
        if (mIsRecordingSessionRequested || mIsRecordingSessionCreated) {
            Log.d(TAG, "Attempting to stop current recording session of camera " + mCameraId);
            try {
                mRecorder.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Attempting to stop recording that wasn't started, got: ", e);
            } catch (RuntimeException e) {
                Log.e(TAG, "Received no data during recording, got: ", e);
            }
            mRecorder.reset();
        } else {
            Log.d(TAG, "Attempting to stop current preview session of camera " + mCameraId);
        }
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
        mIsPreviewSessionRequested = false;
        mIsPreviewSessionCreated = false;
        mIsRecordingSessionRequested = false;
        mIsRecordingSessionCreated = false;
    }

    long getFrameCountOfLastSession() {
        return mSessionCaptureListener.getFrameCount();
    }

    final class SurfacePreviewListener implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Log.d(TAG, "Surface created");
            mIsSurfaceCreated = true;
            mConfigHandler.sendEmptyMessage(MSG_SURFACE_READY);
        }

        @Override
        public void surfaceChanged(
                @NonNull SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "Surface Changed to: " + width + "x" + height);
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
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
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera Opened");
            mCameraDevice = camera;
            mIsCameraConnected = true;
            mConfigHandler.sendEmptyMessage(MSG_CAMERA_OPENED);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mIsCameraConnected = false;
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            mIsCameraConnected = false;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {}
    }

    final class CameraSurfaceInitListener implements Handler.Callback {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch(msg.what) {
                case MSG_CAMERA_OPENED:
                case MSG_SURFACE_READY:
                case MSG_SESSION_REQUESTED:
                    if ((mCameraDevice != null) && mIsCameraConnected && mIsSurfaceCreated) {
                        // Camera and surfaces ready to start new capture session
                        if (mIsPreviewSessionRequested && !mIsPreviewSessionCreated) {
                            // Preview session requested but not created
                            Log.d(TAG, "All conditions satisfied, starting preview session.");
                            createPreviewSession();
                        } else if (mIsRecordingSessionRequested && !mIsRecordingSessionCreated) {
                            // Recording session requested but not created
                            Log.d(TAG, "All conditions satisfied, starting recording session.");
                            createRecordingSession();
                        }
                    }
            }
            return true;
        }
    }

    private void createPreviewSession() {
        try {
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(mSurfaceView.getHolder().getSurface());
            mCaptureRequest = captureRequestBuilder.build();

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mSurfaceView.getHolder().getSurface()));
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs,
                    new SessionExecutor(mSessionHandler), mSessionStateListener);

            sessionConfig.setSessionParameters(mCaptureRequest);
            mCameraDevice.createCaptureSession(sessionConfig);
            mIsPreviewSessionCreated = true;
            Log.d(TAG, "Created preview capture session for camera " + mCameraId);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to create preview capture session with camera " + mCameraId, e);
        }
    }

    private void createRecordingSession() {
        try {

            Log.d(TAG, String.format(
                    "Recorder for camera %s is valid? %b",
                    mCameraId, mRecorder.getSurface().isValid()));

            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.addTarget(mSurfaceView.getHolder().getSurface());
            captureRequestBuilder.addTarget(mRecorder.getSurface());
            mCaptureRequest = captureRequestBuilder.build();

            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(new OutputConfiguration(mSurfaceView.getHolder().getSurface()));
            outputs.add(new OutputConfiguration(mRecorder.getSurface()));
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs,
                    new SessionExecutor(mSessionHandler), mSessionStateListener);

            sessionConfig.setSessionParameters(mCaptureRequest);
            mCameraDevice.createCaptureSession(sessionConfig);
            mIsRecordingSessionCreated = true;
            Log.d(TAG, "Created recording capture session for camera " + mCameraId);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to create recording capture session with camera " + mCameraId, e);
        }
    }


    final class SessionStateListener extends CameraCaptureSession.StateCallback {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            mCaptureSession = session;
            try {
                mSessionCaptureListener.resetFrameCount();
                mCaptureSession.setRepeatingRequest(
                        mCaptureRequest, mSessionCaptureListener, mSessionHandler);
                if (mIsPreviewSessionRequested) {
                    Log.d(TAG, "Successfully started recording session with camera " + mCameraId);
                } else if (mIsRecordingSessionRequested) {
                    mRecorder.start();
                    Log.d(TAG, "Successfully started recording session with camera " + mCameraId);
                }
                Log.d(TAG, "Successfully started recording session with camera " + mCameraId);
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to start camera preview with camera " + mCameraId, e);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Failed to configure session with camera " + mCameraId);
        }
    }

    static final class SessionCaptureListener extends CameraCaptureSession.CaptureCallback {
        private final AtomicLong mFrameCount = new AtomicLong(0);

        @Override
        public void onCaptureCompleted(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
            mFrameCount.incrementAndGet();
        }

        public void resetFrameCount() {
            mFrameCount.set(0);
        }

        public long getFrameCount() {
            return mFrameCount.get();
        }
    }
}
