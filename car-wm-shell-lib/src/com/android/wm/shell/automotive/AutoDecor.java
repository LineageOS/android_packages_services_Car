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

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.window.InputTransferToken;

import com.android.server.utils.Slogf;
import com.android.wm.shell.common.DisplayController;

/**
 * A class representing a decorative element in the automotive UI.  This class manages the
 * lifecycle and properties of the decor view, including its attachment to the parent surface,
 * z-order, bounds, and visibility.
 */
public final class AutoDecor {

    private static final String TAG = "AutoDecor";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final DisplayController mDisplayController;
    private final View mView;
    private final String mDecorName;
    private SurfaceControlViewHost mViewHost;
    private int mZOrder;
    private Rect mBounds;
    private boolean mIsCurrentlyAttached;
    private boolean mIsEverAttached;
    private boolean mIsVisible;

    /**
     * Constructor for AutoDecor.
     *
     * @param context The context.
     * @param displayController The display controller.
     * @param view The view associated with the decor.
     * @param zOrder The z-order of the decor.
     * @param bounds The bounds of the decor.
     * @param decorName The name of the decor.
     */
    AutoDecor(Context context, DisplayController displayController, View view, int zOrder,
            Rect bounds, String decorName) {
        mContext = context;
        mDisplayController = displayController;
        mView = view;
        mDecorName = decorName;
        mZOrder = zOrder;
        mBounds = bounds;
        mIsCurrentlyAttached = false;
        mIsEverAttached = false;
        mIsVisible = false;
    }

    /**
     * Returns the view associated with the decor.
     * @return The view.
     */
    public View getView() {
        return mView;
    }

    /**
     * Returns the z-order of the decor.
     * @return The z-order.
     */
    public int getZOrder() {
        return mZOrder;
    }

    /**
     * Returns the bounds of the decor.
     * @return The bounds.
     */
    public Rect getBounds() {
        return mBounds;
    }

    /**
     * Checks if the decor is currently attached to the parent surface.
     * @return True if attached, false otherwise.
     */
    boolean isCurrentlyAttached() {
        return mIsCurrentlyAttached;
    }

    /**
     * Checks if the decor has ever been attached to the parent surface.
     * @return True if ever attached, false otherwise.
     */
    boolean isEverAttached() {
        return mIsEverAttached;
    }

    /**
     * Checks if the decor is visible.
     * @return True if visible.
     */
    boolean isVisible() {
        return mIsVisible;
    }

    /**
     * Updates the visibility of the decor.
     */
    void updateVisibility(boolean isVisible) {
        mIsVisible = isVisible;
    }

    /**
     * Updates the z-order of the decor.
     */
    void updateZOrder(int zOrder) {
        mZOrder = zOrder;
    }

    /**
     * Updates the bounds of the decor.
     */
    void updateBounds(Rect bounds) {
        mBounds = bounds;
    }

    /**
     * Attaches the decor to the parent surface.
     * @param displayId The display ID.
     * @param parentSurface The parent surface.
     */
    void attachDecorToParentSurface(int displayId, SurfaceControl parentSurface) {
        if (DBG) {
            Slogf.e(TAG, "Adding Decor %s to the parent surface %s for display %d", this,
                    parentSurface, displayId);
        }
        mViewHost = new SurfaceControlViewHost(mContext, mDisplayController.getDisplay(displayId),
                (InputTransferToken) null, "AutoDecor");

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(mBounds.width(),
                mBounds.height(), TYPE_APPLICATION, FLAG_NOT_FOCUSABLE | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSPARENT);
        lp.setTitle(mDecorName);
        lp.setTrustedOverlay();

        mViewHost.setView(mView, lp);

        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();

        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(viewSurface, parentSurface)
                .setPosition(viewSurface, mBounds.left, mBounds.top)
                .setLayer(viewSurface, mZOrder)
                .show(viewSurface);
        t.apply();
        mIsCurrentlyAttached = true;
        mIsEverAttached = true;
    }

    /**
     * Detaches the decor from the parent surface.
     */
    void detachDecorFromParentSurface() {
        if (DBG) {
            Slogf.e(TAG, "Detaching Decor %s", this);
        }
        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(viewSurface, null);
        t.apply();
        mIsCurrentlyAttached = false;
    }

    /**
     * Returns the {@link SurfaceControlViewHost} associated with the decor.
     * @return The SurfaceControlViewHost.
     */
    SurfaceControlViewHost getViewHost() {
        return mViewHost;
    }

    /**
     * Returns the name of the decor.
     * @return The name.
     */
    String getName() {
        return mDecorName;
    }
}
