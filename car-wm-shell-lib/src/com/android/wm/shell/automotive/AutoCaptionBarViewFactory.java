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

package com.android.wm.shell.automotive;

import android.app.ActivityManager;
import android.view.View;

/**
 * A factory for creating caption bar views.
 */
public abstract class AutoCaptionBarViewFactory {
    /**
     * Gets the view for the caption bar.
     *
     * @param taskInfo The running task information.
     * @return The view for the caption bar.
     */
    public View createView(ActivityManager.RunningTaskInfo taskInfo) {
        return null;
    }
}
