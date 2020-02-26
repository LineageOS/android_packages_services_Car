/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.google.android.car.uxr.oas;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ProgressBar;

public final class UxRestriction {

    private static UxRestriction sUxRestriction;

    private static final String TAG = UxRestriction.class.getSimpleName();
    private static final int UXR_OVERLAY_VIEW_MIN_HEIGHT = 10;
    private static final int ATTENTION_BUFFER_THRESHOLD_RESTRICT_TOUCH = 0;
    private static final int ATTENTION_BUFFER_THRESHOLD_ENABLE_TOUCH = 40;
    private static final int SPEEDBUMP_THRESHOLD_TURN_GREEN =
            (int) (ATTENTION_BUFFER_THRESHOLD_ENABLE_TOUCH * 0.7);
    private static final int ATTENTION_BUFFER_ENABLED_BACKGROUND_COLOR = 0xffff0000;
    private static final int ATTENTION_BUFFER_DISABLED_BACKGROUND_COLOR = 0xff111111;
    private static final int ATTENTION_BUFFER_DEFAULT_VALUE = 50;
    private static final Handler sUiHandler = new Handler();

    private boolean mIsTouchRestricted;
    private WindowManager mWindowManager;
    private View mUxrOverlayView;
    private ProgressBar mPbDriverAttentionBuffer;
    private ProgressBar mPbSpeedBump;
    private LayoutParams mLayoutParamsEnableTouch;
    private LayoutParams mLayoutParamsDisableTouch;

    private Drawable mDrawableSpeedBumpCyan;
    private Drawable mDrawableSpeedBumpGreen;

    private UxRestriction() {
        // private constructor to prevent direct instantiation of this class.
    }

    public static UxRestriction getInstance() {
        if (sUxRestriction == null) {
            sUxRestriction = new UxRestriction();
        }
        return sUxRestriction;
    }

    public void enableUxRestriction(Context context) {
        Log.i(TAG, "onCreate()");
        mUxrOverlayView = LayoutInflater.from(context).inflate(R.layout.view_ux_restriction, null);
        mPbDriverAttentionBuffer = mUxrOverlayView.findViewById(R.id.pbDriverAttentionBuffer);
        mPbDriverAttentionBuffer.setBackgroundColor(ATTENTION_BUFFER_ENABLED_BACKGROUND_COLOR);

        mPbSpeedBump = mUxrOverlayView.findViewById(R.id.pbSpeedBump);
        mPbSpeedBump.setMax(ATTENTION_BUFFER_THRESHOLD_ENABLE_TOUCH);
        mDrawableSpeedBumpCyan =
            context.getResources().getDrawable(R.drawable.speedbump_progress_bar_cyan);
        mDrawableSpeedBumpGreen =
            context.getResources().getDrawable(R.drawable.speedbump_progress_bar_green);

        mLayoutParamsEnableTouch =
            new LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                UXR_OVERLAY_VIEW_MIN_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mLayoutParamsEnableTouch.gravity = Gravity.TOP | Gravity.RIGHT;

        mLayoutParamsDisableTouch =
            new LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mLayoutParamsDisableTouch.gravity = Gravity.TOP | Gravity.RIGHT;

        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mWindowManager.addView(mUxrOverlayView, mLayoutParamsEnableTouch);

        setAttentionBufferValue(ATTENTION_BUFFER_DEFAULT_VALUE);
    }

    private void runOnUiThread(Runnable runnable) {
        sUiHandler.post(runnable);
    }

    private void restrictTouch() {
        if (!mIsTouchRestricted) {
            mIsTouchRestricted = true;
            runOnUiThread(() -> mWindowManager.updateViewLayout(
                    mUxrOverlayView, mLayoutParamsDisableTouch));
            Log.i(TAG, "Touch disabled.");
        }
    }

    private void enableTouch() {
        if (mIsTouchRestricted) {
            mIsTouchRestricted = false;
            runOnUiThread(() -> mWindowManager.updateViewLayout(
                    mUxrOverlayView, mLayoutParamsEnableTouch));
            Log.i(TAG, "Touch enabled.");
        }
    }

    public void setAttentionBufferValue(int value) {
        mPbDriverAttentionBuffer.post(() -> mPbDriverAttentionBuffer.setProgress(value));
        if (value <= ATTENTION_BUFFER_THRESHOLD_RESTRICT_TOUCH) {
            restrictTouch();
        } else if (value > ATTENTION_BUFFER_THRESHOLD_ENABLE_TOUCH) {
            enableTouch();
        }

        if (value <= ATTENTION_BUFFER_THRESHOLD_ENABLE_TOUCH) {
            mPbSpeedBump.post(() -> mPbSpeedBump.setProgress(value));
            if (value > SPEEDBUMP_THRESHOLD_TURN_GREEN) {
                mPbSpeedBump.post(() -> mPbSpeedBump.setProgressDrawable(mDrawableSpeedBumpGreen));
            } else {
                mPbSpeedBump.post(() -> mPbSpeedBump.setProgressDrawable(mDrawableSpeedBumpCyan));
            }
        }
    }

    public void disableUxRestriction() {
        if (mUxrOverlayView != null) {
            mWindowManager.removeView(mUxrOverlayView);
        }
    }

    public void destroy() {
        disableUxRestriction();
    }
}
