/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.car.kitchensink.display;

import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManagerGlobal;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import java.util.ArrayList;

/**
 * Tests display mirroring on 2 SurfaceViews.
 */
public final class DisplayMirroringFragment extends Fragment {
    private static final String TAG = DisplayMirroringFragment.class.getSimpleName();

    private DisplayManager mDisplayManager;
    private SurfaceControl mMirrorSurfaceControl1;
    private SurfaceControl mMirrorSurfaceControl2;
    private boolean mMirroring;

    private SurfaceView mSurfaceView1;
    private SurfaceView mSurfaceView2;
    private Spinner mDisplaySpinner;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDisplayManager = getContext().getSystemService(DisplayManager.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.display_mirroring, container, false);

        mSurfaceView1 = view.findViewById(R.id.surface_view_1);
        mSurfaceView2 = view.findViewById(R.id.surface_view_2);
        mDisplaySpinner = view.findViewById(R.id.display_spinner);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mDisplaySpinner.setAdapter(new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, getDisplays()));

        view.findViewById(R.id.start_button).setOnClickListener((v) -> startMirroring());
        view.findViewById(R.id.stop_button).setOnClickListener((v) -> stopMirroring());
        updateUi();
    }

    @Override
    public void onDestroyView() {
        if (mMirroring) {
            stopMirroring();
        }
        super.onDestroyView();
    }

    private void startMirroring() {
        int selectedDisplayId = (Integer) mDisplaySpinner.getSelectedItem();
        mMirrorSurfaceControl1 = mirrorDisplayOnSurfaceView(selectedDisplayId, mSurfaceView1);
        mMirrorSurfaceControl2 = mirrorDisplayOnSurfaceView(selectedDisplayId, mSurfaceView2);
        if (mMirrorSurfaceControl1 == null && mMirrorSurfaceControl2 == null) {
            Toast.makeText(getContext(), "Error while mirroring.", Toast.LENGTH_SHORT);
            return;
        }
        if (mMirrorSurfaceControl1 == null) {
            Toast.makeText(getContext(), "Failed to mirror on SurfaceView1",
                    Toast.LENGTH_SHORT);
        }
        if (mMirrorSurfaceControl2 == null) {
            Toast.makeText(getContext(), "Failed to mirror on SurfaceView2",
                    Toast.LENGTH_SHORT);
        }
        mMirroring = true;
        updateUi();
    }

    private void stopMirroring() {
        releaseMirroredSurfaces();
        mMirroring = false;
        updateUi();
    }

    private void updateUi() {
        getView().findViewById(R.id.start_button).setEnabled(!mMirroring);
        mDisplaySpinner.setEnabled(!mMirroring);
        getView().findViewById(R.id.stop_button).setEnabled(mMirroring);
    }

    private void releaseMirroredSurfaces() {
        SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
        if (mMirrorSurfaceControl1 != null) {
            mTransaction.remove(mMirrorSurfaceControl1);
        }
        if (mMirrorSurfaceControl2 != null) {
            mTransaction.remove(mMirrorSurfaceControl2);
        }
        mTransaction.apply();
        mMirrorSurfaceControl1 = null;
        mMirrorSurfaceControl2 = null;
    }

    /**
     * Returns the surface control for the mirrored surface.
     */
    private SurfaceControl mirrorDisplayOnSurfaceView(int displayId, SurfaceView surfaceView) {
        SurfaceControl mirroredSurfaceControl = mirrorDisplay(displayId);
        if (mirroredSurfaceControl == null || !mirroredSurfaceControl.isValid()) {
            Log.e(TAG, "Failed to mirror display = " + displayId);
            return null;
        }
        SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
        mTransaction
                .show(mirroredSurfaceControl)
                .setScale(mirroredSurfaceControl, 0.5f, 0.5f)
                .reparent(mirroredSurfaceControl, surfaceView.getSurfaceControl())
                .apply();
        return mirroredSurfaceControl;
    }

    private SurfaceControl mirrorDisplay(final int displayId) {
        try {
            SurfaceControl outSurfaceControl = new SurfaceControl();
            WindowManagerGlobal.getWindowManagerService().mirrorDisplay(displayId,
                    outSurfaceControl);
            return outSurfaceControl;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reach window manager", e);
        }
        return null;
    }

    private ArrayList<Integer> getDisplays() {
        ArrayList<Integer> displayIds = new ArrayList<>();
        Display[] displays = mDisplayManager.getDisplays();
        int uidSelf = Process.myUid();
        for (Display disp : displays) {
            if (!disp.hasAccess(uidSelf)) {
                continue;
            }
            displayIds.add(disp.getDisplayId());
        }
        return displayIds;
    }
}
