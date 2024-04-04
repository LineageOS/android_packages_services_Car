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

/**
 * A {@code PanelAnimator} to animate the panel into the close state.
 *
 * This animator resizes the panel into the given bounds using the close panel animation parameters.
 */
public class ClosePanelAnimator extends PanelAnimator {
    private static final Interpolator INTERPOLATOR = new PathInterpolator(0.05f, 0.7f, 0.1f, 1);
    private static final long DURATION = 400;

    private Animation mAnimation;
    private final Rect mBounds;

    /**
     * A {@code PanelAnimator} to animate the panel into the close state.
     *
     * @param panel          The panel on which the animator acts.
     * @param bounds         The final bounds that the panel should animate to.
     * @param animationScale Scaling factor for Animator-based animations.
     */
    public ClosePanelAnimator(ViewGroup panel, Rect bounds, float animationScale) {
        super(panel, animationScale);
        mBounds = bounds;
        mDuration = getScaledDuration(DURATION);
    }

    @Override
    public void animate(Runnable endAction) {
        mAnimation = new BoundsAnimation(mPanel, mBounds, endAction);
        mAnimation.setInterpolator(INTERPOLATOR);
        mAnimation.setDuration(mDuration);
        mPanel.startAnimation(mAnimation);
    }

    @Override
    public void cancel() {
        if (mAnimation != null) {
            mAnimation.cancel();
        }
    }
}
