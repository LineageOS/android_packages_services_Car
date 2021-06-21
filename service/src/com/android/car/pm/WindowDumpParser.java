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

package com.android.car.pm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class to parse the window dump.
 */
class WindowDumpParser {
    private static final String WINDOW_TYPE_APPLICATION_STARTING =  "APPLICATION_STARTING";

    /**
     * Parses the provided window dump and returns the list of windows only for a particular app.
     *
     * @param dump the window dump in string format as returned from `dumpsys window
     *         windows`.
     * @param appPackageName the package name of the app, which the windows are being
     *         requested for.
     * @return a list of parsed {@link Window} objects.
     */
    public static List<Window> getParsedAppWindows(String dump, String appPackageName) {
        Pattern p = Pattern.compile("Window #\\d*.*\\n"
                + ".*mDisplayId=(\\S*).*\\n"
                + ".*package=(\\S*).*\\n"
                + ".*ty=(\\S*)");
        Matcher m = p.matcher(dump);
        List<Window> windows = new ArrayList<>();
        while (m.find()) {
            // Only consider windows for the given appPackageName which are not the splash screen
            // windows.
            // TODO(b/192355798): Revisit this logic as window type can be changed.
            if (Objects.equals(m.group(2), appPackageName)
                    && !Objects.equals(m.group(3), WINDOW_TYPE_APPLICATION_STARTING)) {
                windows.add(new Window(m.group(2), Integer.parseInt(m.group(1))));
            }
        }
        return windows;
    }

    /**
     * A holder class that represents an app's window.
     */
    static class Window {
        private final String mPackageName;
        private final int mDisplayId;

        Window(String packageName, int displayId) {
            mPackageName = packageName;
            mDisplayId = displayId;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getDisplayId() {
            return mDisplayId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Window)) return false;
            Window window = (Window) o;
            return mPackageName.equals(window.mPackageName)
                    && mDisplayId == window.mDisplayId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mDisplayId);
        }

        @Override
        public String toString() {
            return "Window{"
                    + "mPackageName='" + mPackageName + '\''
                    + ", mDisplayId='" + mDisplayId + '\''
                    + '}';
        }
    }
}
