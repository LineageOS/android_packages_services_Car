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

package com.android.car.stats;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.util.StatsEvent;

import com.android.car.CarStatsLog;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

/**
 * A wrapper for {@link CarStatsLog}.
 *
 * <p>This is designed to wrap {@link CarStatsLog} which is a static class, so that client could
 * mock this wrapper for testing.
 */
@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
public class CarStatsLogWrapper {

    /**
     * See {@link CarStatsLog#write(int, boolean)}.
     */
    public void write(int code, boolean arg1) {
        CarStatsLog.write(code, arg1);
    }

    /**
     * See {@link CarStatsLog#write(int, int)}.
     */
    public void write(int code, int arg1) {
        CarStatsLog.write(code, arg1);
    }

    /**
     * See {@link CarStatsLog#write(int, int, byte[])}.
     */
    public void write(int code, int arg1, byte[] arg2) {
        CarStatsLog.write(code, arg1, arg2);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int)}.
     */
    public void write(int code, int arg1, int arg2) {
        CarStatsLog.write(code, arg1, arg2);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int)}.
     */
    public void write(int code, int arg1, int arg2, int arg3) {
        CarStatsLog.write(code, arg1, arg2, arg3);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int, byte[], byte[])}.
     */
    public void write(int code, int arg1, int arg2, int arg3, int arg4, byte[] arg5, byte[] arg6) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int, int, int, int)}.
     */
    public void write(int code, int arg1, int arg2, int arg3, int arg4, int arg5, int arg6,
            int arg7) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int, int, String)}.
     */
    public void write(int code, int arg1, int arg2, int arg3, int arg4, int arg5, String arg6) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int, int, String, String)}.
     */
    public void write(int code, int arg1, int arg2, int arg3, int arg4, int arg5, String arg6,
            String arg7) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    /**
     * See {@link CarStatsLog#write(int, int, int, int, String, String)}.
     */
    public void write(int code, int arg1, int arg2, int arg3, String arg4, String arg5) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5);
    }

    /**
     * See {@link CarStatsLog#write(int, int, long, long, long, long)}.
     */
    public void write(int code, int arg1, long arg2, long arg3, long arg4, long arg5) {
        CarStatsLog.write(code, arg1, arg2, arg3, arg4, arg5);
    }

    /**
     * See {@link CarStatsLog#buildStatsEvent(int, byte[], long)}.
     */
    public StatsEvent buildStatsEvent(int code, byte[] arg1, long arg2) {
        return CarStatsLog.buildStatsEvent(code, arg1, arg2);
    }

    /**
     * See {@link CarStatsLog#buildStatsEvent(int, int, byte[], long)}.
     */
    public StatsEvent buildStatsEvent(int code, int arg1, byte[] arg2, long arg3) {
        return CarStatsLog.buildStatsEvent(code, arg1, arg2, arg3);
    }

    /**
     * See
     * {@link CarStatsLog#buildStatsEvent(int, int, int, int, int, long, long, long, long, long, long)}.
     */
    public StatsEvent buildStatsEvent(int code, int arg1, int arg2, int arg3, int arg4, long arg5,
            long arg6, long arg7, long arg8, long arg9, long arg10) {
        return CarStatsLog.buildStatsEvent(code, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10);
    }


}
