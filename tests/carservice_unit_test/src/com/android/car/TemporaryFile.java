/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car;

import android.annotation.Nullable;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * A utility class that represents a file that needs to exist for the duration of a test.
 *
 * Meant to be used with try-with-resources:
 *
 * try (TemporaryFile tf = new TemporaryFile("myTest")) {
 *     ...
 * } // file gets deleted here
 */
public final class TemporaryFile implements AutoCloseable {
    private File mFile;

    public TemporaryFile(@Nullable String prefix) throws IOException {
        if (prefix == null) {
            prefix = TemporaryFile.class.getSimpleName();
        }
        mFile = File.createTempFile(prefix, String.valueOf(SystemClock.elapsedRealtimeNanos()));
    }

    @Override
    public void close() throws Exception {
        mFile.delete();
    }

    public void write(String s) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(mFile));
        writer.write(s);
        writer.close();
    }

    public File getFile() {
        return mFile;
    }
}
