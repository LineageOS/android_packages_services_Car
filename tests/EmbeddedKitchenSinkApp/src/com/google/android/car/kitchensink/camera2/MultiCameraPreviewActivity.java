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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public final class MultiCameraPreviewActivity extends FragmentActivity {
    private static final String TAG = MultiCameraPreviewActivity.class.getSimpleName();
    private final List<CameraPreviewManager> mCameraPreviewManagerList = new ArrayList<>();
    private final List<SurfaceView> mPreviewSurfaceViewList = new ArrayList<>();
    private final List<CheckBox> mPreviewSelectionCheckBoxList = new ArrayList<>();
    private CameraManager mCameraManager;
    private ListView mDetailsListView;
    private String[] mCameraIds;
    private HandlerThread mSessionHandlerThread;
    private boolean mIsPreviewStarted;
    private MemoryInfo mSessionMemoryInfo;

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

        Button startButton = (Button) findViewById(R.id.start_button);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPreviews();
            }
        });

        Button stopButton = (Button) findViewById(R.id.stop_button);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPreviews();
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
        detailsButton.setText(String.format("Details %s", mCameraIds[camIdx]));
        detailsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewCameraCharacteristics(camIdx);
            }
        });

        SurfaceView surfaceView = (SurfaceView) previewLayout.findViewById(R.id.preview_surface);
        CheckBox previewSelectionCheckBox = (CheckBox) previewLayout.findViewById(
                R.id.preview_checkbox);
        mPreviewSelectionCheckBoxList.add(previewSelectionCheckBox);
        mPreviewSurfaceViewList.add(surfaceView);
    }

    private void startPreviews() {
        // Do nothing if preview has already started
        if (mIsPreviewStarted) {
            Log.i(TAG, "Start preview button pressed when a preview is already running.");
            return;
        }
        // Freeze checkbox selections
        for (CheckBox checkBox : mPreviewSelectionCheckBoxList) {
            checkBox.setEnabled(false);
        }
        // Open Cameras and start previews
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mPreviewSelectionCheckBoxList.get(i).isChecked()) {
                mCameraPreviewManagerList.get(i).startSession();
            }
        }
        // Set flag
        mIsPreviewStarted = true;
    }

    private void stopPreviews() {
        // Do nothing if preview has not been started
        if (!mIsPreviewStarted) {
            Log.i(TAG, "Stop preview button pressed when no preview has been started.");
            return;
        }

        // Unset flag
        mIsPreviewStarted = false;

        // Session memory usage and end cpu usage at the end
        mSessionMemoryInfo = new MemoryInfo();
        Debug.getMemoryInfo(mSessionMemoryInfo);

        // View Session Metrics
        List<String> sessionMetricsInfo = getSessionMetricsInfo(mSessionMemoryInfo);
        mDetailsListView.setAdapter(new ArrayAdapter<String>(
                this, R.layout.camera2_details_list_item, sessionMetricsInfo));

        // Close Cameras that are already open
        for (int i = 0; i < mCameraIds.length; i++) {
            if (mPreviewSelectionCheckBoxList.get(i).isChecked()) {
                mCameraPreviewManagerList.get(i).stopSession();
            }
        }

        // Un-freeze checkbox selections
        for (CheckBox checkBox : mPreviewSelectionCheckBoxList) {
            checkBox.setEnabled(true);
        }

    }

    private List<String> getSessionMetricsInfo(MemoryInfo memInfo) {
        List<String> infoList = new ArrayList<>(Arrays.asList("SESSION MEMORY INFO (kB)"));
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
