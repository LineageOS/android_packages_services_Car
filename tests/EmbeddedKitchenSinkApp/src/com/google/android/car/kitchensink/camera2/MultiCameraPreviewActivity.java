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
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.Debug.MemoryInfo;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.fragment.app.FragmentActivity;

import com.google.android.car.kitchensink.R;

import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public final class MultiCameraPreviewActivity extends FragmentActivity {
    private static final String TAG = MultiCameraPreviewActivity.class.getSimpleName();
    private final List<CameraPreviewManager> mCameraPreviewManagerList = new ArrayList<>();
    private final List<SurfaceView> mPreviewSurfaceViewList = new ArrayList<>();
    private final List<CheckBox> mSelectionCheckBoxList = new ArrayList<>();
    private CameraManager mCameraManager;
    private ListView mDetailsListView;
    private String[] mCameraIds;
    private HandlerThread mSessionHandlerThread;
    private boolean mIsPreviewStarted;
    private boolean mIsRecordingStarted;
    private long mSessionStartTimeMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera2_multi_camera_preview_activity);

        mDetailsListView = (ListView) findViewById(R.id.camera_details_list_view);

        Button quitButton = (Button) findViewById(R.id.quit_button);
        quitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        Button startPreviewButton = (Button) findViewById(R.id.start_preview_button);
        startPreviewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPreviews();
            }
        });

        Button startRecordingButton = (Button) findViewById(R.id.start_recording_button);
        startRecordingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRecording();
            }
        });

        Button stopButton = (Button) findViewById(R.id.stop_button);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSession();
            }
        });

        mCameraManager = getSystemService(CameraManager.class);

        mSessionHandlerThread = new HandlerThread(TAG + "_session_thread");
        mSessionHandlerThread.start();

        // Create a list of camera managers to handle each camera device and their capture sessions
        try {
            mCameraIds = mCameraManager.getCameraIdListNoLazy();
            if (mCameraIds.length == 0) {
                Log.w(TAG, "Camera service reported no cameras connected to device.");
                return;
            }
            addPreviewCells(); // Adds preview cells with one SurfaceView for every camera
            for (int camIdx = 0; camIdx < mCameraIds.length; camIdx++) {
                Log.i(TAG, "Creating preview for camera with ID " + mCameraIds[camIdx]);
                CameraPreviewManager cameraPreviewManager = new CameraPreviewManager(
                        mCameraIds[camIdx],
                        mPreviewSurfaceViewList.get(camIdx),
                        mCameraManager,
                        mSessionHandlerThread
                );
                cameraPreviewManager.openCamera();
                mCameraPreviewManagerList.add(cameraPreviewManager);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera ID list, got:", e);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open camera, got:", e);
        }
    }

    private void addPreviewCells() {
        // Adds preview cells in a square-like grid
        int numColumns = (int) Math.ceil(Math.sqrt(mCameraIds.length));
        int numRows = -Math.floorDiv(-mCameraIds.length, numColumns);  //==ceilDiv(nCameras, nCols)
        int numPadded = numColumns * numRows - mCameraIds.length;
        int numCellsLastRow = numColumns - numPadded;

        LinearLayout previewRoot = (LinearLayout) findViewById(R.id.preview_root);

        // Fill every row completely with preview cells except last row
        for (int i = 0; i < numRows - 1; i++) {
            LinearLayout previewRow = (LinearLayout) View.inflate(
                    this, R.layout.camera2_preview_row, null);
            previewRoot.addView(previewRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f));
            for (int j = 0; j < numColumns; j++) {
                addPreviewSurfaceView(previewRow, i * numColumns + j);
            }
        }

        // Add last row
        LinearLayout lastPreviewRow = (LinearLayout) (LinearLayout) View.inflate(
                this, R.layout.camera2_preview_row, null);
        previewRoot.addView(lastPreviewRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f));
        for (int j = 0; j < numCellsLastRow; j++) {
            addPreviewSurfaceView(lastPreviewRow, (numRows - 1) * numColumns + j);
        }
        // Pad rest of the row with empty cells
        for (int j = numCellsLastRow; j < numColumns; j++) {
            LinearLayout emptyCell = (LinearLayout) View.inflate(
                    this, R.layout.camera2_preview_empty_cell, null);
            lastPreviewRow.addView(emptyCell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f));
        }
    }

    private void addPreviewSurfaceView(ViewGroup parent, int camIdx) {
        LinearLayout previewLayout = (LinearLayout) View.inflate(
                this, R.layout.camera2_preview_cell, null);
        parent.addView(previewLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));

        Button detailsButton = (Button) previewLayout.findViewById(R.id.details_button);
        detailsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewCameraCharacteristics(camIdx);
            }
        });

        CheckBox selectionCheckBox = (CheckBox) previewLayout.findViewById(R.id.selection_checkbox);
        selectionCheckBox.setText(
                getString(R.string.camera2_selection_checkbox, mCameraIds[camIdx]));
        SurfaceView surfaceView = (SurfaceView) previewLayout.findViewById(R.id.preview_surface);

        mSelectionCheckBoxList.add(selectionCheckBox);
        mPreviewSurfaceViewList.add(surfaceView);
    }

    private void startPreviews() {
        // Do nothing if a session has already started
        if (mIsPreviewStarted || mIsRecordingStarted) {
            Log.i(TAG, "Start preview button pressed when a session is already running.");
            return;
        }
        // Freeze checkbox selections
        for (CheckBox checkBox : mSelectionCheckBoxList) {
            checkBox.setEnabled(false);
        }
        // Open Cameras and start previews
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mSelectionCheckBoxList.get(i).isChecked()) {
                mCameraPreviewManagerList.get(i).startPreviewSession();
            }
        }

        // Set Start Time
        mSessionStartTimeMs = SystemClock.elapsedRealtime();

        // Set flag
        mIsPreviewStarted = true;
    }

    private void startRecording() {
        // Do nothing if a session has already started
        if (mIsPreviewStarted || mIsRecordingStarted) {
            Log.i(TAG, "Start recording button pressed when a session is already running.");
            return;
        }
        // Freeze checkbox selections
        for (CheckBox checkBox : mSelectionCheckBoxList) {
            checkBox.setEnabled(false);
        }

        // Get file prefix from current time
        String dateTimeString =
                new SimpleDateFormat("yyyy-MM-dd_hh.mm.ss", Locale.US)
                        .format(Calendar.getInstance().getTime());
        String filePathPrefix = String.format("%s/camera2_video_%s",
                getExternalFilesDir(null), dateTimeString);

        // Open Cameras and start recording
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mSelectionCheckBoxList.get(i).isChecked()) {
                mCameraPreviewManagerList.get(i).startRecordingSession(filePathPrefix);
            }
        }

        // Set Start Time
        mSessionStartTimeMs = SystemClock.elapsedRealtime();

        // Set flag
        mIsRecordingStarted = true;
    }

    private void stopSession() {
        // Do nothing if no preview has been started
        if (!mIsPreviewStarted && !mIsRecordingStarted) {
            Log.i(TAG, "Stop button pressed when no session has been started.");
            return;
        }

        // Unset flags
        mIsPreviewStarted = false;
        mIsRecordingStarted = false;

        // Session memory usage
        MemoryInfo sessionMemoryInfo = new MemoryInfo();
        Debug.getMemoryInfo(sessionMemoryInfo);

        // Get Frame Counts
        Map<String, Long> frameCountMap = getSessionFrameCounts();

        // Get End Time
        long sessionDurationMs = SystemClock.elapsedRealtime() - mSessionStartTimeMs;

        // View Session Metrics
        List<String> sessionMetricsInfoText = getSessionMemoryInfoText(sessionMemoryInfo);
        sessionMetricsInfoText.add("");
        sessionMetricsInfoText.addAll(getSessionFpsInfoText(frameCountMap, sessionDurationMs));
        mDetailsListView.setAdapter(new ArrayAdapter<String>(
                this, R.layout.camera2_details_list_item, sessionMetricsInfoText));

        // Stop camera sessions that have been started
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mSelectionCheckBoxList.get(i).isChecked()) {
                mCameraPreviewManagerList.get(i).stopSession();
            }
        }

        // Un-freeze checkbox selections
        for (CheckBox checkBox : mSelectionCheckBoxList) {
            checkBox.setEnabled(true);
        }
    }

    private Map<String, Long> getSessionFrameCounts() {
        Map<String, Long> frameCountMap = new HashMap<>();
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mSelectionCheckBoxList.get(i).isChecked()) {
                frameCountMap.put(
                        mCameraIds[i],
                        mCameraPreviewManagerList.get(i).getFrameCountOfLastSession());
            }
        }
        return frameCountMap;
    }

    private static List<String> getSessionFpsInfoText(
            Map<String, Long> frameCountMap, Long sessionDurationMs) {
        List<String> infoList = new ArrayList<>(List.of("SESSION FPS INFO (Hz)"));
        for (Map.Entry<String, Long> entry : frameCountMap.entrySet()) {
            String cameraId = entry.getKey();
            long frameCount = entry.getValue();
            infoList.add(String.format(
                    "Effective FPS of camera %s: %.2f",
                    cameraId,
                    (1000.0 * frameCount) / sessionDurationMs));
        }
        return infoList;
    }

    private List<String> getSessionMemoryInfoText(MemoryInfo memInfo) {
        List<String> infoList = new ArrayList<>(List.of("SESSION MEMORY INFO (kB)"));
        Map<String, String> memStats = memInfo.getMemoryStats();
        for (Map.Entry<String, String> memStat : memStats.entrySet()) {
            infoList.add(String.format("%s: %s", memStat.getKey(), memStat.getValue()));
        }
        return infoList;
    }

    private void viewCameraCharacteristics(int cameraIdx) {
        List<String> detailsList = new ArrayList<>(Arrays.asList(
                String.format("CAMERA %s INFO", mCameraIds[cameraIdx])));
        detailsList.addAll(getCameraCharacteristics(cameraIdx));
        mDetailsListView.setAdapter(new ArrayAdapter<String>(
                this, R.layout.camera2_details_list_item, detailsList));
    }

    private List<String> getCameraCharacteristics(int cameraIdx) {
        CameraCharacteristics characteristics;
        try {
            characteristics = mCameraManager.getCameraCharacteristics(mCameraIds[cameraIdx]);
        } catch (CameraAccessException e) {
            Log.e(TAG, String.format("Camera %s disconnected while fetching characteristics.",
                    mCameraIds[cameraIdx]), e);
            return Arrays.asList("Camera disconnected...");
        } catch (IllegalStateException e) {
            Log.e(TAG, String.format("Attempting to fetch characteristics of unknown camera ID %s.",
                    mCameraIds[cameraIdx]), e);
            return Arrays.asList("Invalid camera ID...");
        }
        List<CameraCharacteristics.Key<?>> allKeys = characteristics.getKeys();
        List<String> detailsList = new ArrayList<>();
        for (CameraCharacteristics.Key<?> key: allKeys) {
            try {
                Object val = characteristics.get(key);
                if (val == null) {
                    detailsList.add(String.format("%s: (null)", key.getName()));
                } else if (val.getClass().isArray()) {
                    Object[] valAsObjectArray = asObjectArray(val);
                    detailsList.add(String.format("%s: %s", key.getName(),
                            Arrays.deepToString(valAsObjectArray)));
                } else {
                    detailsList.add(String.format("%s: %s", key.getName(), val));
                }

            } catch (IllegalArgumentException e) {
                Log.e(TAG, String.format("Invalid key %s found in camera ID %s", key.getName(),
                        mCameraIds[cameraIdx]));
                detailsList.add(String.format("%s: INVALID KEY", key.getName()));
            }
        }
        return detailsList;
    }

    private static Object[] asObjectArray(Object array) {
        int length = Array.getLength(array);
        Object[] ret = new Object[length];
        for (int i = 0; i < length; i++) {
            ret[i] = Array.get(array, i);
        }
        return ret;
    }

    @Override
    protected void onDestroy() {
        for (CameraPreviewManager cameraPreviewManager : mCameraPreviewManagerList) {
            cameraPreviewManager.closeCamera();
        }
        try {
            if (mSessionHandlerThread != null) {
                mSessionHandlerThread.quitSafely();
                mSessionHandlerThread.join();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while closing session thread.", e);
        }
        super.onDestroy();
    }
}
