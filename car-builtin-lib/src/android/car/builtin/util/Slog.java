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

package android.car.builtin.util;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Wrapper class for {@code android.util.Slog}. Check the class for API documentation.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class Slog {
    private Slog() {
        throw new UnsupportedOperationException();
    }

    /** Check {@code android.util.Slog}. */
    public static int v(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.v(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int v(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.v(tag, msg, tr);
    }

    /** Check {@code android.util.Slog}. */
    public static int d(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.d(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int d(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.d(tag, msg, tr);
    }

    /** Check {@code android.util.Slog}. */
    public static int i(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.i(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int i(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.i(tag, msg, tr);
    }

    /** Check {@code android.util.Slog}. */
    public static int e(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.e(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int e(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.e(tag, msg, tr);
    }

    /** Check {@code android.util.Slog}. */
    public static int w(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.w(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int w(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.w(tag, msg, tr);
    }

    /** Check {@code android.util.Slog}. */
    public static int wtf(@NonNull String tag, @NonNull String msg) {
        return android.util.Slog.wtf(tag, msg);
    }

    /** Check {@code android.util.Slog}. */
    public static int wtf(@NonNull String tag, @NonNull String msg, @NonNull Throwable tr) {
        return android.util.Slog.wtf(tag, msg, tr);
    }
}
