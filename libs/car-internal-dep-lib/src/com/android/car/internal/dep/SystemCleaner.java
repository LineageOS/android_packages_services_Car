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

package com.android.car.internal.dep;

import java.lang.ref.Cleaner;

/**
 * A wrapper for {@link android.system.SystemCleaner}.
 *
 * @hide
 */
public final class SystemCleaner {

    private SystemCleaner() {
        throw new UnsupportedOperationException("SystemCleaner must be used statically");
    }

    /**
     * Return a single Cleaner that's shared across the entire process. Thread-safe.
     * Unlike normal Cleaners, uncaught exceptions during cleaning will throw an uncaught
     * exception from the daemon running the cleaning action. This will normally cause the
     * process to crash, and thus cause the problem to be reported.
     */
    public static Cleaner cleaner() {
        return android.system.SystemCleaner.cleaner();
    }
}
