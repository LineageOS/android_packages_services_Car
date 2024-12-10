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

package android.car.test.util;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

public final class DiskUtils {
    private static final String TAG = DiskUtils.class.getSimpleName();
    private static final int DISK_DELAY_MS = 4000;

    /**
     * Writes bytes to a given file
     *
     * @param uniqueFile             File
     * @param size                   Number of bytes to be written
     *
     * @return number of bytes written
     *
     * @throws IOException           Thrown when file doesn't exist or when fos.write fails
     * @throws InterruptedException  Thrown when the current thread is interrupted
     */
    public static long writeToDisk(File uniqueFile, long size)
            throws IOException, InterruptedException {
        if (!uniqueFile.exists()) {
            throw new FileNotFoundException("file '"
                    + uniqueFile.getAbsolutePath() + "' doesn't exist");
        }

        if (uniqueFile.isDirectory()) {
            throw new FileNotFoundException(uniqueFile.getAbsolutePath()
                    + " is a directory, not a file.");
        }

        long writtenBytes = 0;
        try (FileOutputStream fos = new FileOutputStream(uniqueFile)) {
            Log.d(TAG, "Attempting to write " + size + " bytes");
            writtenBytes = writeToFos(fos, size);
            fos.getFD().sync();
            Thread.sleep(DISK_DELAY_MS);
        }

        return writtenBytes;
    }

    /**
     * Writes bytes to a given filestream
     *
     * @param fos          Filestream
     * @param maxSize      Number of bytes to be written
     *
     * @return number of bytes written
     *
     * @throws IOException Thrown when fos.write fails
     */
    private static long writeToFos(FileOutputStream fos, long maxSize) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        long writtenBytes = 0;
        while (maxSize != 0) {
            // The total available free memory can be calculated by adding the currently allocated
            // memory that is free plus the total memory available to the process which hasn't been
            // allocated yet.
            long totalFreeMemory =
                    runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
            int writeSize = Math.toIntExact(Math.min(totalFreeMemory, maxSize));
            Log.i(TAG, "writeSize: " + writeSize);
            if (writeSize == 0) {
                Log.d(TAG,
                        "Ran out of memory while writing, exiting early with writtenBytes: "
                        + writtenBytes);
                return writtenBytes;
            }
            try {
                fos.write(new byte[writeSize]);
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            maxSize -= writeSize;
            writtenBytes += writeSize;
            if (writeSize > 0 && maxSize > 0) {
                Log.i(TAG, "Total bytes written: " + writtenBytes + "/"
                        + (writtenBytes + maxSize));
            }
        }
        return writtenBytes;
    }
}
