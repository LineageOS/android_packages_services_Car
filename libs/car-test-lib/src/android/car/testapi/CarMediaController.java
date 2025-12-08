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

package android.car.testapi;

import android.annotation.UserIdInt;
import android.car.media.CarMediaManager;
import android.car.media.ICarMediaSourceListener;
import android.content.ComponentName;

import java.util.List;
import java.util.Set;

/** Interface to control FakeCarMediaService. */
public interface CarMediaController {
    /**
     * Sets the default media source.
     *
     * @param mediaSource The {@link ComponentName} to set as the default.
     * @param userId The user id.
     */
    void setDefaultMediaSource(ComponentName mediaSource, @UserIdInt int userId);

    /**
     * Clears the last media sources.
     *
     * @param mode The media source mode.
     * @param userId The user id.
     */
    void clearLastMediaSources(@CarMediaManager.MediaSourceMode int mode, @UserIdInt int userId);

    /**
     * Adds media sources to the last media sources.
     *
     * @param mode The media source mode.
     * @param mediaSources The list of {@link ComponentName} to add.
     * @param userId The user id.
     */
    void addLastMediaSources(@CarMediaManager.MediaSourceMode int mode,
            List<ComponentName> mediaSources, @UserIdInt int userId);

    /**
     * Gets the registered media source listener.
     *
     * @param mode The media source mode.
     * @param userId The user id.
     * @return The set of {@link ICarMediaSourceListener} registered.
     */
    Set<ICarMediaSourceListener> getRegisteredMediaSourceListener(int mode, int userId);
}
