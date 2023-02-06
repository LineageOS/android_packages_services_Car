/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.app;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceControl;

/**
 * This is a blueprint to implement the host part of {@link CarTaskViewClient}.
 *
 * @hide
 */
// STOPSHIP(b/266718395): Change it to system API once it's ready to release.
// @SystemApi
public interface CarTaskViewHost {
    /** Releases the resources held by this task view's host side. */
    void release();

    /**
     * See {@link com.android.wm.shell.TaskView#startActivity(PendingIntent, Intent,
     * ActivityOptions, Rect)}
     */
    void startActivity(
            PendingIntent pendingIntent, Intent intent, Bundle options, Rect launchBounds);

    /**
     * Notifies the host side that the client surface has been created.
     *
     * @param control the {@link SurfaceControl} of the surface that has been created.
     */
    void notifySurfaceCreated(SurfaceControl control);

    /**
     * Sets the bounds of the window for the underlying Task.
     *
     * @param windowBoundsOnScreen the new bounds in screen coordinates.
     */
    void setWindowBounds(Rect windowBoundsOnScreen);

    /** Notifies the host side that the client surface has been destroyed. */
    void notifySurfaceDestroyed();

    /** Brings the embedded Task to the front in the WM Hierarchy. */
    void showEmbeddedTask();
}
