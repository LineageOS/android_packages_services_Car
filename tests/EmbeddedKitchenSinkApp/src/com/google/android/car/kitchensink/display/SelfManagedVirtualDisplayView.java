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

import static android.widget.Toast.LENGTH_SHORT;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.car.kitchensink.R;

import java.io.PrintWriter;

/**
 * Custom view that hosts a virtual display and a button to create / remove it
 */

public final class SelfManagedVirtualDisplayView extends LinearLayout {

    private static final String TAG = SelfManagedVirtualDisplayView.class.getSimpleName();

    private static final String NO_ID_TEXT = "N/A";

    private final VirtualDisplayView mVirtualDisplayView;

    private final LinearLayout mHeader;
    private final EditText mDisplayIdEditText;
    private final Button mCreateDisplayButton;
    private final Button mDeleteDisplayButton;

    public SelfManagedVirtualDisplayView(Context context, AttributeSet attrs) {
        this(context, VirtualDisplayView.getName(context, attrs));
    }

    public SelfManagedVirtualDisplayView(Context context, String name) {
        super(context);

        inflate(context, R.layout.self_managed_virtual_display_view, this);

        mHeader = findViewById(R.id.header);
        mVirtualDisplayView = findViewById(R.id.virtual_display);
        mDisplayIdEditText = findViewById(R.id.display_id);
        mCreateDisplayButton = findViewById(R.id.create);
        mDeleteDisplayButton = findViewById(R.id.delete);

        if (name != null) {
            mVirtualDisplayView.setName(name);
        }
        toggleCreateDeleteButtons(/* create= */ true);
        mDisplayIdEditText.setText(NO_ID_TEXT);
        mCreateDisplayButton.setOnClickListener((v) -> createDisplayAndToastMessage());
        mDeleteDisplayButton.setOnClickListener((v) -> deleteDisplayAndToastMessage());

    }

    void setHeaderVisible(boolean visible) {
        toggleView(mHeader, visible);
    }

    String getName() {
        return mVirtualDisplayView.getName();
    }

    void release() {
        mVirtualDisplayView.release();
    }

    void dump(String prefix, PrintWriter writer, String[] args) {
        dumpView(prefix, writer, mHeader, "Buttons panel");
        dumpView(prefix, writer, mCreateDisplayButton, "Create display button");
        dumpView(prefix, writer, mDeleteDisplayButton, "Delete display button");

        writer.printf("%sDisplay id: %s\n", prefix, mDisplayIdEditText.getText());
        writer.printf("%sVirtual Display View:\n", prefix);
        mVirtualDisplayView.dump(prefix + "  ", writer);
    }

    private void createDisplayAndToastMessage() {
        Log.i(TAG, "Creating virtual display");
        try {
            int displayId = createDisplay();
            logAndToastMessage("Created virtual display with id %d", displayId);
        } catch (Exception e) {
            logAndToastError(e, "Failed to create virtual display");
        }
    }

    /**
     * Creates the display and return its id.
     */
    int createDisplay() {
        int displayId = mVirtualDisplayView.createVirtualDisplay();
        mDisplayIdEditText.setText(String.valueOf(displayId));
        toggleCreateDeleteButtons(/* create= */ false);
        return displayId;
    }

    private void deleteDisplayAndToastMessage() {
        Log.i(TAG, "Deleting display");
        try {
            deleteDisplay();
            logAndToastMessage("Virtual display deleted");
        } catch (Exception e) {
            logAndToastError(e, "Failed to delete virtual display");
        }
    }

    /**
     * Deletes the display.
     */
    void deleteDisplay() {
        mVirtualDisplayView.deleteVirtualDisplay();
        mDisplayIdEditText.setText(NO_ID_TEXT);
        toggleCreateDeleteButtons(/* create= */ true);
    }

    private void toggleCreateDeleteButtons(boolean create) {
        toggleView(mCreateDisplayButton, create);
        toggleView(mDeleteDisplayButton, !create);
    }

    // TODO(b/231499090): move plumbing below to common code

    private void dumpView(String prefix, PrintWriter writer, View view, String name) {
        writer.printf("%s%s: %s %s\n", prefix, name,
                (view.isEnabled() ? "enabled" : "disabled"),
                visibilityToString(view.getVisibility()));
    }

    private void toggleView(View view, boolean on) {
        view.setEnabled(on);
        view.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    protected void logAndToastMessage(String format, Object...args) {
        String message = String.format(format, args);
        Log.i(TAG, message);
        Toast.makeText(getContext(), message, LENGTH_SHORT).show();
    }

    protected void printMessage(PrintWriter writer, String format, Object...args) {
        String message = String.format(format, args);
        writer.printf("%s\n", message);
    }

    protected void logAndToastError(Exception e, String format, Object...args) {
        String message = String.format(format, args);
        if (e != null) {
            Log.e(TAG, message, e);
        } else {
            Log.e(TAG, message);
        }
        Toast.makeText(getContext(), message, LENGTH_SHORT).show();
    }

    protected void printError(PrintWriter writer, Exception e, String format, Object...args) {
        String message = String.format(format, args);
        if (e != null) {
            writer.printf("%s: %s\n", message, e);
        } else {
            writer.printf("%s\n", message);
        }
    }

    public static String visibilityToString(int visibility) {
        switch (visibility) {
            case View.VISIBLE:
                return "VISIBLE";
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "UNKNOWN-" + visibility;
        }
    }
}
