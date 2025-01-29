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

import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.View;

/**
 * Interface for updating decoration.
 *
 * <p>Provides methods for updating the bounds, Z-order, and view
 * of a surface. All updates are applied within a
 * {@link SurfaceControl.Transaction}.
 */
public interface AutoDecor {
    /**
     * Updates the bounds of the associated surface.
     *
     * @param t       The transaction to apply the bounds change to.
     * @param bounds  The new bounds for the surface.
     */
    void setBounds(SurfaceControl.Transaction t, Rect bounds);

    /**
     * Updates the Z-order of the associated surface.
     *
     * @param t      The transaction to apply the Z-order change to.
     * @param zLayer The new Z-layer for the surface.
     */
    void setZOrder(SurfaceControl.Transaction t, int zLayer);

    /**
     * Updates the view associated with the surface.
     *
     * @param t    The transaction to apply the view update to.
     * @param view The updated view.
     */
    void setView(SurfaceControl.Transaction t, View view);

    /**
     * Updates decor visibility. It will change the root view visibility.
     *
     * @param t         The transaction to apply the decor update to.
     * @param isVisible True to make the surface visible, false to hide it.
     */
    void setVisibility(SurfaceControl.Transaction t, boolean isVisible);
}
