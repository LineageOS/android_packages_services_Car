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
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Provides a virtual display that could be used by other apps.
 */
public final class VirtualDisplayFragment extends Fragment {

    private static final String TAG = VirtualDisplayFragment.class.getSimpleName();

    private static final String NO_ID_TEXT = "N/A";

    private static final String ARG_MAXIMIZE = "--maximize";
    private static final String ARG_MINIMIZE = "--minimize";

    private VirtualDisplayView mVirtualDisplayView;

    private LinearLayout mButtonsLinearLayout;
    private EditText mDisplayIdEditText;
    private Button mCreateDisplayButton;
    private Button mDeleteDisplayButton;
    private Button mMaximizeDisplayButton;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.virtual_display, container, false);

        mVirtualDisplayView = view.findViewById(R.id.virtual_display);
        mDisplayIdEditText = view.findViewById(R.id.display_id);
        mCreateDisplayButton = view.findViewById(R.id.create);
        mDeleteDisplayButton = view.findViewById(R.id.delete);
        mMaximizeDisplayButton = view.findViewById(R.id.maximize);
        mButtonsLinearLayout = view.findViewById(R.id.buttons_panel);

        toggleCreateDeleteButtons(/* create=*/ true);
        mDisplayIdEditText.setText(NO_ID_TEXT);
        mCreateDisplayButton.setOnClickListener((v) -> createDisplay());
        mDeleteDisplayButton.setOnClickListener((v) -> deleteDisplay());
        mMaximizeDisplayButton.setOnClickListener((v) -> maximizeDisplay());

        return view;
    }

    @Override
    public void onDestroyView() {
        mVirtualDisplayView.release();

        super.onDestroyView();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {

        if (args != null && args.length > 0) {
            String arg = args[0];
            switch (arg) {
                case ARG_MINIMIZE:
                    minimizeOrMaximizeDisplay(writer, /* maximize= */ false);
                    break;
                case ARG_MAXIMIZE:
                    minimizeOrMaximizeDisplay(writer, /* maximize= */ true);
                    break;
                default:
                    writer.printf("Invalid args: %s\n", Arrays.toString(args));
            }
            return;
        }

        dumpView(prefix, writer, mButtonsLinearLayout, "Buttons panel");
        dumpView(prefix, writer, mCreateDisplayButton, "Create display");
        dumpView(prefix, writer, mDeleteDisplayButton, "Delete display");
        dumpView(prefix, writer, mMaximizeDisplayButton, "Maximize display");
        writer.printf("%sDisplay id: %s\n", prefix, mDisplayIdEditText.getText());
        writer.printf("%sVirtual Display View\n", prefix);
        mVirtualDisplayView.dump(prefix + "  ", writer);
    }

    private void dumpView(String prefix, PrintWriter writer, View view, String name) {
        writer.printf("%s%s: %s %s\n", prefix, name,
                (view.isEnabled() ? "enabled" : "disabled"),
                visibilityToString(view.getVisibility()));
    }

    private void toggleCreateDeleteButtons(boolean create) {
        toggleView(mCreateDisplayButton, create);
        toggleView(mDeleteDisplayButton, !create);
    }

    private void toggleView(View view, boolean on) {
        view.setEnabled(on);
        view.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void createDisplay() {
        Log.i(TAG, "Creating virtual display");
        try {
            int displayId = mVirtualDisplayView.createVirtualDisplay();
            logAndToast("Created virtual display with id %d", displayId);

            mDisplayIdEditText.setText(String.valueOf(displayId));
            toggleCreateDeleteButtons(/* create= */ false);
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
            toggleCreateDeleteButtons(/* create= */ true);
        } catch (Exception e) {
            logAndToast(e, "Failed to delete virtual display");
        }
    }

    private void maximizeDisplay() {
        minimizeOrMaximizeDisplay(/* writer= */ null, /* maximize= */ true);
    }

    private void minimizeOrMaximizeDisplay(PrintWriter writer, boolean maximize) {
        String msg1;
        if (maximize) {
            msg1 = "Maximizing display. To minimize, run:";
        } else {
            msg1 = "Minimizing display. To maximize, run:";
        }
        String pkg = getContext().getPackageName();
        String activity = KitchenSinkActivity.class.getSimpleName();
        String msg2 = String.format("adb shell dumpsys activity %s/.%s%s", pkg, activity,
                (maximize ? ARG_MINIMIZE : ARG_MAXIMIZE));
        logAndToast(msg1);
        logAndToast(Toast.LENGTH_SHORT, msg2);
        if (writer != null) {
            writer.println(msg1);
            writer.println(msg2);
        }
        toggleView(mButtonsLinearLayout, !maximize);

        // TODO(b/231499090): also hide the "show kitchensink menu" panel
    }


    private void logAndToast(String format, Object...args) {
        logAndToast(Toast.LENGTH_SHORT, format, args);
    }

    private void logAndToast(int duration, String format, Object...args) {
        String message = String.format(format, args);
        Log.i(TAG, message);
        Toast.makeText(getContext(), message, duration).show();
    }

    private void logAndToast(Exception e, String format, Object...args) {
        String message = String.format(format, args);
        Log.e(TAG, message, e);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    private static String visibilityToString(int visibility) {
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
