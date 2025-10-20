/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.car.kitchensink.perfetto;

import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.service.tracing.TraceReportService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PerfettoReportService extends TraceReportService {
    public static final String TAG = "PerfettoReportService";

    @Override
    public void onCreate() {
        Log.d(TAG, "Executing PerfettoReporter#onCreate");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            // SECURITY: Trace uploading is protected by the permission
            // android.permission.BIND_TRACE_REPORT_SERVICE which is only defined in T. Pre-T, any
            // side-loaded app can claim this permission and get access to reporting traces.
            throw new AssertionError("Trace reporting service should not be enabled pre-T.");
        }
        super.onCreate();
    }

    @Override
    public void onReportTrace(TraceParams args) {
        Log.d(TAG, "Received trace with UUID '" + args.getUuid().toString() + "'");
        String fileName = "perfetto_" + args.getUuid().toString() + ".trace";

        try {
            File f = new File(getFilesDir(), fileName);
            boolean created = f.createNewFile();
            if (!created) {
                throw new IllegalStateException("Failed to create file");
            }
            try (AutoCloseInputStream i = new AutoCloseInputStream(args.getFd())) {
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(i.readAllBytes());
                }
            }
            Log.d(TAG, "Wrote the trace to " + f.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("IO Exception", e);
        }
    }
}
