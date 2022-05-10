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

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Provides a virtual display that could be used by other apps.
 */
public final class VirtualDisplayFragment extends Fragment {

    private static final String TAG = VirtualDisplayFragment.class.getSimpleName();

    private static final String NO_ID_TEXT = "N/A";

    private VirtualDisplayView mVirtualDisplayView;

    private EditText mDisplayIdEditText;
    private Button mCreateDisplayButton;
    private Button mDeleteDisplayButton;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.virtual_display, container, false);

        mVirtualDisplayView = view.findViewById(R.id.virtual_display);
        mDisplayIdEditText = view.findViewById(R.id.display_id);
        mCreateDisplayButton = view.findViewById(R.id.create);
        mDeleteDisplayButton = view.findViewById(R.id.delete);

        mDisplayIdEditText.setText(NO_ID_TEXT);
        mCreateDisplayButton.setOnClickListener((v) -> createDisplay());
        mDeleteDisplayButton.setOnClickListener((v) -> deleteDisplay());

        return view;
    }

    @Override
    public void onDestroyView() {
        mVirtualDisplayView.release();

        super.onDestroyView();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        dumpButton(prefix, writer, mCreateDisplayButton, "Create display");
        dumpButton(prefix, writer, mDeleteDisplayButton, "Delete display");
        writer.printf("%sDisplay id: %s\n", prefix, mDisplayIdEditText.getText());
        writer.printf("%sVirtual Display View\n", prefix);
        mVirtualDisplayView.dump(prefix + "  ", writer);
    }

    private void dumpButton(String prefix, PrintWriter writer, Button button, String name) {
        writer.printf("%s%s button: %s\n", prefix, name,
                (button.isEnabled() ? "enabled" : "disabled"));
    }

    private void createDisplay() {
        Log.i(TAG, "Creating virtual display");
        try {
            int displayId = mVirtualDisplayView.createVirtualDisplay();
            logAndToast("Created virtual display with id %d", displayId);

            mDisplayIdEditText.setText(String.valueOf(displayId));
            mCreateDisplayButton.setEnabled(false);
            mDeleteDisplayButton.setEnabled(true);
        } catch (Exception e) {
            logAndToast(e, "Failed to create virtual display");
        }
    }

    private void deleteDisplay() {
        Log.i(TAG, "Deleting display");
        try {
            mVirtualDisplayView.deleteVirtualDisplay();
            logAndToast("Virtual display deleted");

            mDisplayIdEditText.setText(NO_ID_TEXT);
            mCreateDisplayButton.setEnabled(true);
            mDeleteDisplayButton.setEnabled(false);
        } catch (Exception e) {
            logAndToast(e, "Failed to delete virtual display");
        }
    }

    private void logAndToast(String format, Object...args) {
        String message = String.format(format, args);
        Log.i(TAG, message);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void logAndToast(Exception e, String format, Object...args) {
        String message = String.format(format, args);
        Log.e(TAG, message, e);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }
}
