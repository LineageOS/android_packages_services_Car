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

package androidx.car.app.activity;


import static com.android.car.pm.ActivityBlockingActivityTest.DoActivity.DIALOG_TITLE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * An activity to represent a template activity in tests.
 */
public class CarAppActivity extends Activity {
    public static final String ACTION_SHOW_DIALOG = "SHOW_DIALOG";
    private final ShowDialogReceiver mShowDialogReceiver = new ShowDialogReceiver();

    private class ShowDialogReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            showDialog();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.registerReceiver(mShowDialogReceiver, new IntentFilter(ACTION_SHOW_DIALOG));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.unregisterReceiver(mShowDialogReceiver);
    }

    private void showDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(DIALOG_TITLE)
                .setMessage("Message")
                .create();
        dialog.show();
    }
}
