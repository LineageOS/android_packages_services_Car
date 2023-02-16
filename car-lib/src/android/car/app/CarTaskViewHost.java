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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.car.annotation.ApiRequirements;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceControl;

/**
 * This is a blueprint to implement the host part of {@link CarTaskViewClient}.
 *
 * @hide
 */
@SystemApi
public interface CarTaskViewHost {
    /** Releases the resources held by this task view's host side. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void release();

    /**
     * See {@link com.android.wm.shell.TaskView#startActivity(PendingIntent, Intent,
     * ActivityOptions, Rect)}
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void startActivity(
            @NonNull PendingIntent pendingIntent, @Nullable Intent intent, @NonNull Bundle options,
            @Nullable Rect launchBounds);

    /**
     * Notifies the host side that the client surface has been created.
     *
     * @param control the {@link SurfaceControl} of the surface that has been created.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void notifySurfaceCreated(@NonNull SurfaceControl control);

    /**
     * Sets the bounds of the window for the underlying Task.
     *
     * @param windowBoundsOnScreen the new bounds in screen coordinates.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void setWindowBounds(@NonNull Rect windowBoundsOnScreen);

    /** Notifies the host side that the client surface has been destroyed. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void notifySurfaceDestroyed();

    /** Brings the embedded Task to the front in the WM Hierarchy. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    void showEmbeddedTask();
}
