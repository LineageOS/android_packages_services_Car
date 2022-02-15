/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.builtin;

import android.annotation.SystemApi;

/**
 * Class to hold car builtin library's common stuffs.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class CarBuiltin {
    private CarBuiltin() {
        throw new UnsupportedOperationException();
    }

    /**
     * Represents a minor version change of car builtin library for the same
     * {@link android.os.Build.VERSION#SDK_INT}.
     *
     * <p>It will reset to {@code 0} whenever {@link android.os.Build.VERSION#SDK_INT} is updated
     * and will increase by {@code 1} if car builtin API is changed with the same
     * {@link android.os.Build.VERSION#SDK_INT}. Client should check this version to use APIs which
     * were added in a minor only version update.
     */
    public static final int API_VERSION_MINOR_INT = 0;
}
