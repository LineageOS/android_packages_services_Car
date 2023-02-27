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

package com.android.car.portraitlauncher.panel;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.car.carlauncher.CarTaskView;
import com.android.car.portraitlauncher.R;
import com.android.car.portraitlauncher.panel.animation.PanelAnimator;

/**
 * A view container used to display CarTaskViews.
 *
 * This panel can transition between various states, e.g. open, close and full screen.
 * When panel is in open state it shows a grab bar to the users which can be dragged to transition
 * to the other states.
 */
public class TaskViewPanel extends LinearLayout {

    private static final String TAG = TaskViewPanel.class.getSimpleName();
    private static final boolean DBG = Build.IS_DEBUGGABLE;

    /** The properties of each valid state of the panel. */
    public static class State {
        /** The bounds used for the panel when put in this state. */
        Rect mBounds = new Rect();
        /** The insets used for the panel. */
        Insets mInsets = Insets.NONE;
        /** Whether or not the panel should display the grip bar. */
        private boolean mHasGripBar;
        /** Whether the panel is visible when put in this state. */
        private boolean mIsVisible;
        /** Whether the panel is considered full screen when put in this state. */
        private boolean mIsFullScreen;

        public State(boolean hasGripBar, boolean isVisible, boolean isFullScreen) {
            mHasGripBar = hasGripBar;
            mIsVisible = isVisible;
            mIsFullScreen = isFullScreen;
        }

        boolean hasGripBar() {
            return mHasGripBar;
        }

        /** Whether the panel in this state has any visible parts. */
        public boolean isVisible() {
            return mIsVisible;
        }

        /** Is this state considered full screen or not. */
        public boolean isFullScreen() {
            return mIsFullScreen;
        }
    }

    /** Notifies the listener when the panel state changes. */
    public interface OnStateChangeListener{
        /**
         * Called right before the panel state changes.
         *
         * @param oldState The state from which the transition is about to start.
         * @param newState The final state of the panel after the transition.
         * @param animated If the transition is animated.
         */
        void onStateChangeStart(State oldState, State newState, boolean animated);

        /**
         * Called right after the panel state changes.
         *
         * If the transition is animated, this method would be called after the animation.
         * @param oldState The state from which the transition started.
         * @param newState The final state of the panel after the transition.
         * @param animated If the transition is animated.
         */
        void onStateChangeEnd(State oldState, State newState, boolean animated);
    }

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    /** The properties of the panel when in {@code open} state. */
    private State mOpenState;
    /** The properties of the panel when in {@code close} state. */
    private State mCloseState;
    /** The properties of the panel when in {@code full screen} state. */
    private State mFullScreenState;

    /**
     * The current state of the panel.
     *
     * When transitioning from an state to another, this value will show the final state of the
     * animation.
     */
    private State mActiveState;

    /** An optional listener to observe when the panel state changes. */
    private OnStateChangeListener mOnStateChangeListener;

    /** The drag threshold after which the panel transitions to the close mode. */
    private int mDragThreshold;

    /** The top margin used for the panel in open state. */
    private int mDefaultTopMargin;

    /** The height of the grip bar. */
    private int mGripBarHeight;

    /** The grip bar used to drag the panel. */
    private GripBarView mGripBar;

    /** Internal container of the {@code CarTaskView}. */
    private ViewGroup mTaskViewContainer;

    /** The {@code CarTaskView} embedded in this panel. This is the main content of the panel. */
    private CarTaskView mTaskView;

    /** The {@code Animator} used to animate the panel. */
    private PanelAnimator mAnimator;

    /** Shows whether the panel is animating or there is no ongoing animation. */
    private boolean mIsAnimating;

    /** The last reported window bounds of the task view. */
    private Rect mTaskViewWindowBounds;


    public TaskViewPanel(Context context) {
        this(context, null);
    }

    public TaskViewPanel(Context context,
            @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewPanel(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewPanel(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mDragThreshold = (int) getResources().getDimension(R.dimen.panel_drag_threshold);
        mDefaultTopMargin = (int) getResources().getDimension(R.dimen.panel_default_top_margin);

        mAnimator = new PanelAnimator(this);

        mOpenState = new State(/* hasGripBar = */ true, /* isVisible = */ true,
                /* isFullScreen */false);
        mCloseState = new State(/* hasGripBar = */ true, /* isVisible = */ false,
                /* isFullScreen */false);
        mFullScreenState = new State(/* hasGripBar = */ false, /* isVisible = */ true,
                /* isFullScreen */true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mGripBar = findViewById(R.id.grip_bar);
        mTaskViewContainer = findViewById(R.id.task_view_container);
        setupGrabBar();
        setActiveState(mCloseState, /* animate = */ false);
    }

    /** Whether the panel is in the open state. */
    public boolean isOpen() {
        return mActiveState == mOpenState;
    }

    /** Whether the panel is in full screen state. */
    public boolean isFullScreen() {
        return mActiveState.isFullScreen();
    }

    /** Whether the panel is actively animating. */
    public boolean isAnimating() {
        return mIsAnimating;
    }

    /** Transitions the panel into the open state. */
    public void openPanel(boolean animated) {
        setActiveState(mOpenState, animated);
    }

    /** Transitions the panel into the close state. */
    public void closePanel(boolean animated) {
        setActiveState(mCloseState, animated);
    }

    /** Transitions the panel into the full screen state. */
    public void openFullScreenPanel(boolean animated) {
        setActiveState(mFullScreenState, animated);
    }

    /** Sets the state change listener for the panel. */
    public void setOnStateChangeListener(OnStateChangeListener listener) {
        mOnStateChangeListener = listener;
    }

    /**
     * Returns the grip bar bounds of the current state.
     *
     * Note that the visual grip bar bounds might be different from the value here during
     * transition animations.
     *
     * @param bounds The {@code Rect} that is used to return the data.
     */
    public void getGripBarBounds(Rect bounds) {
        if (mActiveState.hasGripBar()) {
            bounds.set(mActiveState.mBounds);
            bounds.bottom = mActiveState.mBounds.top + mGripBarHeight;
        } else {
            bounds.setEmpty();
        }
    }

    /** Updates the {@code TaskView} used in the panel. */
    public void setTaskView(CarTaskView taskView) {
        logIfDebuggable("TaskView updated " + taskView);
        mTaskView = taskView;
        mTaskViewContainer.addView(mTaskView);
    }

    /** Refreshes the panel according to the given {@code Theme}. */
    public void refresh(Resources.Theme theme) {
        int backgroundColor = getResources().getColor(R.color.car_background, theme);
        mTaskViewContainer.setBackgroundColor(backgroundColor);
        mGripBar.refresh(theme);
    }


    /**
     * Updates the Obscured touch region of the panel.
     * This need to be called if there are areas that the task view should not receive a touch
     * input due to other blocking views in the view hierarchy.
     */
    public void setObscuredTouchRegion(Region region) {
        if (mTaskView == null) {
            return;
        }
        mTaskView.setObscuredTouchRegion(region);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGrabBar() {
        mGripBarHeight = (int) getResources().getDimension(R.dimen.panel_grip_bar_height);
        mGripBar.setOnTouchListener(new OnPanelDragListener() {
            @Override
            public void onDragBegin() {}

            @Override
            public void onDrag(int deltaX, int deltaY) {
                deltaY = Math.max(0, deltaY);
                Rect rect = new Rect(mActiveState.mBounds);
                rect.offset(0, deltaY);
                updateBounds(rect);
            }

            @Override
            public void onDragEnd(int deltaX, int deltaY) {
                deltaY = Math.max(0, deltaY);
                if (deltaY > mDragThreshold) {
                    setActiveState(mCloseState, /* animate = */ true);
                } else {
                    setActiveState(mOpenState, /* animate = */ true);
                }
            }
        });
    }

    /** Returns the bounds of the task view once fully transitioned to the active state */
    private Rect getTaskViewBounds(State state) {
        Rect bounds = new Rect(state.mBounds);
        bounds.inset(mActiveState.mInsets);

        if (state.hasGripBar()) {
            bounds.top += mGripBarHeight;
        }

        return bounds;
    }

    /** Updates the insets of the taskView for non-fullscreen states. */
    public void setInsets(Insets insets) {
        if (insets.equals(mOpenState.mInsets)) {
            return;
        }
        mOpenState.mInsets = insets;
        mCloseState.mInsets = insets;
        updateInsets(mActiveState.mInsets);
        recalculateBounds();
        updateBounds(mActiveState.mBounds);
        post(() -> mTaskView.onLocationChanged());
    }

    /** Should be called when the view is no longer in use. */
    public void onDestroy() {
        mTaskView = null;
    }

    /** Should be called when the parent dimension changes. */
    public void onParentDimensionChanged() {
        int parentWidth = ((ViewGroup) getParent()).getWidth();
        int parentHeight = ((ViewGroup) getParent()).getHeight();

        Log.w(TAG, "onDimensionChanged: " + parentWidth + " " + parentHeight);

        recalculateBounds();

        post(() -> mTaskView.onLocationChanged());
        updateBounds(mActiveState.mBounds);
    }


    private void recalculateBounds() {
        int parentWidth = ((ViewGroup) getParent()).getWidth();
        int parentHeight = ((ViewGroup) getParent()).getHeight();

        int panelHeight = parentWidth + mOpenState.mInsets.bottom + mGripBarHeight + 1;
        int panelTop = Math.max(0, parentHeight - panelHeight);
        mOpenState.mBounds.set(0, panelTop, parentWidth, parentHeight);
        mCloseState.mBounds.set(0, parentHeight, parentWidth,
                parentHeight + mOpenState.mBounds.height());
        mFullScreenState.mBounds.set(0, 0, parentWidth, parentHeight);
    }

    private void setActiveState(State toState, boolean animated) {
        Log.w(TAG, "SetActiveState to " + toState.mBounds);
        State fromState = mActiveState;

        onStateChangeStart(fromState, toState, animated);

        mActiveState = toState;

        updateInsets(mActiveState.mInsets);
        updateTaskViewWindowBounds();
        mGripBar.setVisibility(toState.hasGripBar() ? VISIBLE : GONE);

        if (animated) {
            animateToState(mActiveState, () -> {
                Log.w(TAG, "On animation end");
                onStateChangeEnd(fromState, toState, animated);
            });
        } else {
            onStateChangeEnd(fromState, toState, animated);
        }
    }

    private void onStateChangeStart(State fromState, State toState, boolean animated) {
        mIsAnimating = animated;
        if (mOnStateChangeListener != null) {
            mOnStateChangeListener.onStateChangeStart(fromState, toState, animated);
        }
    }
    private void onStateChangeEnd(State fromState, State toState, boolean animated) {
        if (mTaskView != null) {
            mTaskView.setZOrderOnTop(false);
        }

        if (mOnStateChangeListener != null) {
            mOnStateChangeListener.onStateChangeEnd(fromState, toState, animated);
        }
        mIsAnimating = false;
    }

    private void updateTaskViewWindowBounds() {
        // Due to performance issues we only set the window bounds when the panel is transitioning
        // to a visible state and only if the window bounds is not changed since the last visible
        // state.
        Rect taskViewBounds = getTaskViewBounds(mActiveState);
        if (mActiveState.isVisible() && !taskViewBounds.equals(mTaskViewWindowBounds)) {
            mTaskViewWindowBounds = taskViewBounds;
            logIfDebuggable("TaskView bounds: " + mTaskViewWindowBounds);
            mTaskView.setWindowBounds(taskViewBounds);
        }
    }

    private void animateToState(State state, Runnable onAnimationEnd) {
        Animation animation;
        if (state == mOpenState) {
            animation = mAnimator.createOpenPanelAnimation(state.mBounds, onAnimationEnd);
        } else if (state == mCloseState) {
            animation = mAnimator.createClosePanelAnimation(state.mBounds, onAnimationEnd);
        } else if (state == mFullScreenState) {
            // To reduce the visual glitches, resize the panel before starting the animation.
            Rect bounds = new Rect(mFullScreenState.mBounds);
            bounds.offset(mOpenState.mBounds.left, mOpenState.mBounds.top);
            updateBounds(bounds);
            animation = mAnimator.createFullScreenPanelAnimation(mFullScreenState.mBounds,
                    onAnimationEnd);
        } else {
            onAnimationEnd();
            return;
        }
        // Start the animation on the next cycle to avoid relayout conflicts.
        post(() -> startAnimation(animation));
    }

    private void updateInsets(Insets insets) {
        mTaskViewContainer.setPadding(insets.left, insets.top, insets.right, insets.bottom);
    }

    private void updateBounds(Rect bounds) {
        final FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.topMargin = bounds.top;
        layoutParams.rightMargin = bounds.right;
        layoutParams.width = bounds.width();
        layoutParams.height = bounds.height();
        setLayoutParams(layoutParams);
    }
}