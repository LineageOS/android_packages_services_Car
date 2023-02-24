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

package com.android.car.portraitlauncher.panel.animation;

import android.graphics.Rect;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** A helper class to generate various animations for the panel. */
public class PanelAnimator {
    private static final Interpolator OPEN_PANEL_INTERPOLATOR =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1);
    private static final long OPEN_PANEL_DURATION = 417;

    private static final Interpolator CLOSE_PANEL_INTERPOLATOR =
            new PathInterpolator(0.6f, 0, 1, 1);
    private static final long CLOSE_PANEL_DURATION = 400;

    private static final Interpolator FULL_SCREEN_PANEL_INTERPOLATOR =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1);
    private static final long FULL_SCREEN_PANEL_MOVE_DURATION = 400;

    private ViewGroup mPanel;

    public PanelAnimator(ViewGroup panel) {
        mPanel = panel;
    }

    /**
     * Creates an animation to the open state.
     *
     * @param toBounds The final bounds of the panel.
     * @param onAnimationEnd A {@code Runnable} to be called at the end of the animation.
     */
    public Animation createOpenPanelAnimation(Rect toBounds, Runnable onAnimationEnd) {
        Animation anim = new BoundsAnimation(mPanel, toBounds, onAnimationEnd);
        anim.setInterpolator(OPEN_PANEL_INTERPOLATOR);
        anim.setDuration(OPEN_PANEL_DURATION);
        return anim;
    }

    /**
     * Creates an animation to the close state.
     *
     * @param toBounds The final bounds of the panel.
     * @param onAnimationEnd A {@code Runnable} to be called at the end of the animation.
     */
    public Animation createClosePanelAnimation(Rect toBounds, Runnable onAnimationEnd) {
        Animation anim = new BoundsAnimation(mPanel, toBounds, onAnimationEnd);
        anim.setInterpolator(CLOSE_PANEL_INTERPOLATOR);
        anim.setDuration(CLOSE_PANEL_DURATION);
        return anim;
    }

    /**
     * Creates an animation to the full screen state.
     *
     * @param toBounds The final bounds of the panel.
     * @param onAnimationEnd A {@code Runnable} to be called at the end of the animation.
     */
    public Animation createFullScreenPanelAnimation(Rect toBounds, Runnable onAnimationEnd) {
        Animation anim = new BoundsAnimation(mPanel, toBounds, onAnimationEnd);
        anim.setInterpolator(FULL_SCREEN_PANEL_INTERPOLATOR);
        anim.setDuration(FULL_SCREEN_PANEL_MOVE_DURATION);
        return anim;
    }
}
