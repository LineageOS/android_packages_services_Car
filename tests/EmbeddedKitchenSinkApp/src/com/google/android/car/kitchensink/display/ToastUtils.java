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

package com.google.android.car.kitchensink.display;

import static android.widget.Toast.LENGTH_SHORT;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

final class ToastUtils {

    private static final String TAG = ToastUtils.class.getSimpleName();

    static void logAndToastMessage(Context context, String format, Object...args) {
        String message = String.format(format, args);
        Log.i(TAG, message);
        Toast.makeText(context, message, LENGTH_SHORT).show();
    }

    static void logAndToastError(Context context, Exception e, String format, Object...args) {
        String message = String.format(format, args);
        if (e != null) {
            Log.e(TAG, message, e);
        } else {
            Log.e(TAG, message);
        }
        Toast.makeText(context, message, LENGTH_SHORT).show();
    }

    private ToastUtils() {
    }
}
