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

package com.android.car.audio;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DEBUGGING_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import android.annotation.IntDef;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

final class CarVolumeEventFlag {

    public static final int FLAG_EVENT_VOLUME_NONE = 1;
    public static final int FLAG_EVENT_VOLUME_CHANGE = 1 << 1;
    public static final int FLAG_EVENT_VOLUME_MUTE = 1 << 2;
    public static final int FLAG_EVENT_VOLUME_BLOCKED = 1 << 3;
    public static final int FLAG_EVENT_VOLUME_ATTENUATED = 1 << 4;
    public static final int FLAG_EVENT_VOLUME_LIMITED = 1 << 5;

    @IntDef(prefix = {"FLAG_EVENT_VOLUME_"}, value = {
            FLAG_EVENT_VOLUME_NONE,
            FLAG_EVENT_VOLUME_CHANGE,
            FLAG_EVENT_VOLUME_MUTE,
            FLAG_EVENT_VOLUME_BLOCKED,
            FLAG_EVENT_VOLUME_ATTENUATED,
            FLAG_EVENT_VOLUME_LIMITED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeEventFlags {}

    private static final SparseArray<String> FLAG_NAMES = new SparseArray<>();

    static {
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_NONE, "FLAG_EVENT_VOLUME_NONE");
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_CHANGE, "FLAG_EVENT_VOLUME_CHANGE");
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_MUTE, "FLAG_EVENT_VOLUME_MUTE");
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_BLOCKED, "FLAG_EVENT_VOLUME_BLOCKED");
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_ATTENUATED, "FLAG_EVENT_VOLUME_ATTENUATED");
        FLAG_NAMES.put(FLAG_EVENT_VOLUME_LIMITED, "FLAG_EVENT_VOLUME_LIMITED");
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private CarVolumeEventFlag() {
        throw new UnsupportedOperationException("CarVolumeEventFlag is non-instantiable");
    }

    static boolean hasInvalidFlag(int flags) {
        if (flags == 0) {
            return true;
        }

        for (int index = 0; index < FLAG_NAMES.size(); index++) {
            int flag = FLAG_NAMES.keyAt(index);
            flags &= ~flag;
        }

        return flags != 0;
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DEBUGGING_CODE)
    static String flagsToString(int flags) {
        StringBuilder stringBuffer = new StringBuilder();
        for (int index = 0; index < FLAG_NAMES.size(); index++) {
            int flag = FLAG_NAMES.keyAt(index);
            if ((flags & flag) == 0) {
                continue;
            }

            if (stringBuffer.length() > 0) {
                stringBuffer.append(',');
            }
            stringBuffer.append(FLAG_NAMES.valueAt(index));
            flags &= ~flag;

        }
        if (flags != 0) {
            if (stringBuffer.length() > 0) {
                stringBuffer.append(',');
            }
            stringBuffer.append(flags);
        }
        return stringBuffer.toString();
    }
}
