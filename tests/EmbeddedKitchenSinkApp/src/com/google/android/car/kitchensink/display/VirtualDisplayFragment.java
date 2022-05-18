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

import static com.google.android.car.kitchensink.KitchenSinkActivity.DUMP_ARG_CMD;
import static com.google.android.car.kitchensink.KitchenSinkActivity.DUMP_ARG_FRAGMENT;

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

    private static final String CMD_HELP = "help";
    private static final String CMD_CREATE = "create";
    private static final String CMD_DELETE = "delete";
    private static final String CMD_MAXIMIZE = "maximize";
    private static final String CMD_MINIMIZE = "minimize";

    public static final String FRAGMENT_NAME = "virtual display";

    private VirtualDisplayView mVirtualDisplayView;

    private LinearLayout mButtonsLinearLayout;
    private EditText mDisplayIdEditText;
    private Button mCreateDisplayButton;
    private Button mDeleteDisplayButton;
    private Button mMaximizeButton;
    private boolean mMaximized;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        Log.v(TAG, "onCreateView(): mMaximizeButton=" + mMaximizeButton);

        if (mMaximizeButton == null) {
            mMaximizeButton = new Button(getContext());
            mMaximizeButton.setText("Maximize");
            Log.v(TAG, "Created maximize button: " + mMaximizeButton);
            ((KitchenSinkActivity) getActivity()).addHeaderView(mMaximizeButton);
        }

        View view = inflater.inflate(R.layout.virtual_display, container, false);
        mVirtualDisplayView = view.findViewById(R.id.virtual_display);
        mDisplayIdEditText = view.findViewById(R.id.display_id);
        mCreateDisplayButton = view.findViewById(R.id.create);
        mDeleteDisplayButton = view.findViewById(R.id.delete);
        mButtonsLinearLayout = view.findViewById(R.id.buttons_panel);

        toggleCreateDeleteButtons(/* create= */ true);
        toggleView(mMaximizeButton, /* on= */ true);
        mDisplayIdEditText.setText(NO_ID_TEXT);
        mCreateDisplayButton.setOnClickListener((v) -> createDisplay());
        mDeleteDisplayButton.setOnClickListener((v) -> deleteDisplay());
        mMaximizeButton.setOnClickListener((v) -> maximizeDisplay());

        return view;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        Log.v(TAG, "onHiddenChanged(hidden=" + hidden + "): mMaximizeButton=" + mMaximizeButton);
        if (mMaximizeButton == null) {
            // NOTE: onHiddenChanged(false) is called before onCreateView(...)
            Log.v(TAG, "Ignoring onHiddenChanged() call as fragment was not created yet");
            return;
        }
        toggleView(mMaximizeButton, /* on= */ !hidden);
    }

    @Override
    public void onDestroyView() {
        mVirtualDisplayView.release();

        super.onDestroyView();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        Log.v(TAG, "dump(): " + Arrays.toString(args));

        if (args != null && args.length > 0 && args[0].equals(DUMP_ARG_CMD)) {
            runCmd(writer, args);
            return;
        }

        dumpView(prefix, writer, mButtonsLinearLayout, "Buttons panel");
        dumpView(prefix, writer, mCreateDisplayButton, "Create display");
        dumpView(prefix, writer, mDeleteDisplayButton, "Delete display");
        dumpView(prefix, writer, mMaximizeButton, "Maximize screen");
        writer.printf("%sDisplay id: %s\n", prefix, mDisplayIdEditText.getText());
        writer.printf("%sVirtual Display View\n", prefix);
        writer.printf("%sMaximized: %b\n", prefix, mMaximized);
        writer.printf("%sVirtual Display View:\n", prefix);
        mVirtualDisplayView.dump(prefix + "  ", writer);
    }

    private void runCmd(PrintWriter writer, String[] args) {
        if (args.length < 2) {
            writer.println("missing command\n");
            return;
        }
        String cmd = args[1];
        switch (cmd) {
            case CMD_HELP:
                showAvailableCommands(writer);
                break;
            case CMD_CREATE:
                createDisplay(writer);
                break;
            case CMD_DELETE:
                deleteDisplay(writer);
                break;
            case CMD_MAXIMIZE:
                minimizeOrMaximizeDisplay(writer, /* maximize= */ true);
                break;
            case CMD_MINIMIZE:
                minimizeOrMaximizeDisplay(writer, /* maximize= */ false);
                break;
            default:
                showAvailableCommands(writer);
                writer.printf("Invalid cmd: %s\n", Arrays.toString(args));
        }
        return;
    }

    private void showAvailableCommands(PrintWriter writer) {
        writer.println("Available commands:\n");
        showCommandHelp(writer, "Shows this help message.", CMD_HELP);
        showCommandHelp(writer, "Maximizes the display view so it takes the whole screen.",
                CMD_MAXIMIZE);
        showCommandHelp(writer, "Minimizes the display view so the screen show the controls.",
                CMD_MINIMIZE);
        showCommandHelp(writer, "Creates the virtual display.",
                CMD_CREATE);
        showCommandHelp(writer, "Deletes the virtual display.",
                CMD_DELETE);
    }

    private void showCommandHelp(PrintWriter writer, String description, String cmd,
            String... args) {
        writer.printf("%s", cmd);
        if (args != null) {
            for (String arg : args) {
                writer.printf(" %s", arg);
            }
        }
        writer.println(":");
        writer.printf("  %s\n\n", description);
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

    // TODO(b/231499090): use custom Writer interface to reuse logic for toast / writer below

    private void createDisplay() {
        Log.i(TAG, "Creating virtual display");
        try {
            int displayId = mVirtualDisplayView.createVirtualDisplay();
            logAndToastMessage("Created virtual display with id %d", displayId);

            mDisplayIdEditText.setText(String.valueOf(displayId));
            toggleCreateDeleteButtons(/* create= */ false);
        } catch (Exception e) {
            logAndToastError(e, "Failed to create virtual display");
        }
    }

    private void createDisplay(PrintWriter writer) {
        Log.i(TAG, "Creating virtual display");
        try {
            int displayId = mVirtualDisplayView.createVirtualDisplay();
            printMessage(writer, "Created virtual display with id %d", displayId);

            mDisplayIdEditText.setText(String.valueOf(displayId));
            toggleCreateDeleteButtons(/* create= */ false);
        } catch (Exception e) {
            printError(writer, e, "Failed to create virtual display");
        }
    }

    private void deleteDisplay() {
        Log.i(TAG, "Deleting display");
        try {
            mVirtualDisplayView.deleteVirtualDisplay();
            logAndToastMessage("Virtual display deleted");

            mDisplayIdEditText.setText(NO_ID_TEXT);
            toggleCreateDeleteButtons(/* create= */ true);
        } catch (Exception e) {
            logAndToastError(e, "Failed to delete virtual display");
        }
    }

    private void deleteDisplay(PrintWriter writer) {
        Log.i(TAG, "Deleting display");
        try {
            mVirtualDisplayView.deleteVirtualDisplay();
            printMessage(writer, "Virtual display deleted");

            mDisplayIdEditText.setText(NO_ID_TEXT);
            toggleCreateDeleteButtons(/* create= */ true);
        } catch (Exception e) {
            printError(writer, e, "Failed to delete virtual display");
        }
    }

    private void maximizeDisplay() {
        if (mMaximized) {
            logAndToastError(/* exception= */ null, "Already maximized");
            return;
        }
        String msg1 = "Maximizing display. To minimize, run:";
        String pkg = getContext().getPackageName();
        String activity = KitchenSinkActivity.class.getSimpleName();
        String msg2 = String.format("adb shell 'dumpsys activity %s/.%s %s \"%s\" %s %s'",
                pkg, activity, DUMP_ARG_FRAGMENT, FRAGMENT_NAME, DUMP_ARG_CMD, CMD_MINIMIZE);
        logAndToastMessage(msg1);
        logAndToastMessage(msg2);
        minimizeOrMaximizeViews(/* maximize= */ true);
    }

    private void minimizeOrMaximizeDisplay(PrintWriter writer, boolean maximize) {
        if (maximize && mMaximized) {
            printError(writer, /* exception= */ null, "Already maximized");
            return;
        }
        if (!maximize && !mMaximized) {
            printError(writer, /* exception= */ null, "Already minimized");
            return;
        }
        String msg1;
        if (maximize) {
            msg1 = "Maximizing display. To minimize, run:";
        } else {
            msg1 = "Minimizing display. To maximize, run:";
        }
        String pkg = getContext().getPackageName();
        String activity = KitchenSinkActivity.class.getSimpleName();
        String msg2 = String.format("adb shell 'dumpsys activity %s/.%s %s \"%s\" %s %s'",
                pkg, activity, DUMP_ARG_FRAGMENT, FRAGMENT_NAME, DUMP_ARG_CMD,
                (maximize ? CMD_MINIMIZE : CMD_MAXIMIZE));
        printMessage(writer, msg1);
        printMessage(writer, msg2);
        minimizeOrMaximizeViews(maximize);
    }

    private void minimizeOrMaximizeViews(boolean maximize) {
        mMaximized = maximize;
        boolean visible = !maximize;
        toggleView(mButtonsLinearLayout, visible);

        ((KitchenSinkActivity) getActivity()).setHeaderVisibility(!maximize);
    }

    // TODO(b/231499090): move plumbing below to common code

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

    protected void printError(PrintWriter writer, Exception exception,
            String format, Object...args) {
        String message = String.format(format, args);
        if (exception != null) {
            writer.printf("%s: %s\n", message, exception);
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
