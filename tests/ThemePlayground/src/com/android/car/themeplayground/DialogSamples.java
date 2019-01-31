/*
 * Copyright (C) 2019 The Android Open Source Project.
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

package com.android.car.themeplayground;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.widget.Button;
import android.widget.TextView;


/**
 * Activity that shows different dialogs from the device default theme.
 */
public class DialogSamples extends AbstractSampleActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.onActivityCreateSetTheme(this);
        setContentView(R.layout.dialog_samples);

        Button mShowDialogBT = findViewById(R.id.showDialogBT);
        Button mShowDialogWithCheckboxBT = findViewById(R.id.showDialogWithCheckboxBT);
        setupBackgroundColorControls(R.id.dialogLayout);
        mShowDialogBT.setOnClickListener(v -> openDialog(false));
        mShowDialogWithCheckboxBT.setOnClickListener(v -> openDialog(true));
    }


    private void openDialog(boolean showCheckbox) {

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new ContextThemeWrapper(this, R.style.Theme_Testing_Dialog_Alert));

        if (showCheckbox) {
            // Set Custom Title
            TextView title = new TextView(this);
            // Title Properties
            title.setText("Custom Dialog Box");
            builder.setCustomTitle(title);
            builder.setMultiChoiceItems(new CharSequence[]{"I am a checkbox"},
                    new boolean[]{false},
                    (dialog, which, isChecked) -> {
                    });
        } else {
            builder.setTitle("Standard Alert Dialog")
                    .setMessage("With a message to show.");
        }

        builder.setPositiveButton("OK", (dialoginterface, i) -> {
        }).setNegativeButton("CANCEL",
                (dialog, which) -> {
                });
        builder.show();
    }
}
