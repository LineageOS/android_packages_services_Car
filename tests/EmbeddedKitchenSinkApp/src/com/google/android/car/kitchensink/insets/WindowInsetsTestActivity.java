/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.car.kitchensink.insets;

import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;

import com.android.car.ui.AlertDialogBuilder;

import com.google.android.car.kitchensink.R;

/**
 * {@link WindowInsetsTestActivity} shows usage of {@link WindowInsetsController} api's to hide
 * systems bars to present the activity in full screen.
 */
public final class WindowInsetsTestActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSysUI();
        setContentView(R.layout.fullscreen_activity);

    }

    @Override
    protected void onStart() {
        super.onStart();
        findViewById(R.id.cancel_button).setOnClickListener(l -> finish());
        findViewById(R.id.show_dialog_button).setOnClickListener(l -> showDialog());
        findViewById(R.id.show_car_ui_dialog_button).setOnClickListener(l -> showCarUiDialog());
    }

    private void hideSysUI() {
        View decorView = getWindow().getDecorView();
        WindowInsetsController windowInsetsController = decorView.getWindowInsetsController();
        windowInsetsController.hide(WindowInsets.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void showDialog() {
        new AlertDialog.Builder(/* context= */ this)
                .setTitle(R.string.dialog_title)
                .setNeutralButton(R.string.dismiss_btn_text, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create()
                .show();
    }

    private void showCarUiDialog() {
        new AlertDialogBuilder(/* context= */ this)
                .setTitle(R.string.car_ui_dialog_title)
                .create()
                .show();
    }
}
