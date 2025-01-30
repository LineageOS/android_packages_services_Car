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
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.annotations.ShellMainThread;

/**
 * Managers the auto decor surface.
 */
public class AutoDecorImpl implements AutoDecor {
    private static final String TAG = "AutoDecor";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final DisplayController mDisplayController;
    private final ShellExecutor mShellExecutor;
    private View mView;
    private int mZOrder;
    private Rect mBounds;
    private SurfaceControlViewHost mViewHost;
    private boolean mIsAttached;

    AutoDecorImpl(Context context, DisplayController displayController, View view, int zOrder,
            Rect bounds, @ShellMainThread ShellExecutor shellExecutor) {
        mContext = context;
        mDisplayController = displayController;
        mView = view;
        mZOrder = zOrder;
        mBounds = bounds;
        mShellExecutor = shellExecutor;
        mIsAttached = false;
    }

    boolean isAttached() {
        return mIsAttached;
    }

    void detachDecorFromParentSurface(SurfaceControl.Transaction t) {
        if (DBG) {
            Slogf.e(TAG, "Detaching Decor %s", this);
        }
        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();
        t.reparent(viewSurface, null);
    }

    void attachDecorToParentSurface(SurfaceControl.Transaction t, int displayId,
            SurfaceControl parentSurface) {
        if (DBG) {
            Slogf.e(TAG, "Adding Decor %s to the parent surface %s for display %d", this,
                    parentSurface, displayId);
        }
        mViewHost = new SurfaceControlViewHost(mContext, mDisplayController.getDisplay(displayId),
                (InputTransferToken) null, "AutoDecor");
        mViewHost.setView(mView, getLayoutParams());

        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();

        t.reparent(viewSurface, parentSurface)
                .setPosition(viewSurface, mBounds.left, mBounds.top)
                .setLayer(viewSurface, mZOrder)
                .show(viewSurface);
        mIsAttached = true;
    }

    @ShellMainThread
    @Override
    public void setBounds(SurfaceControl.Transaction t, Rect bounds) {
        if (DBG) {
            Slogf.d(TAG, "Updating bounds for decor %s to the new bounds %s", this, bounds);
        }

        mBounds = bounds;
        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();

        t.setPosition(viewSurface, mBounds.left, mBounds.top)
                .show(viewSurface);

        // TODO(b/388083112): Figure out a better way to achieve it.
        t.addTransactionCommittedListener(mShellExecutor::execute,
                () -> mViewHost.relayout(getLayoutParams()));
    }

    @ShellMainThread
    @Override
    public void setZOrder(SurfaceControl.Transaction t, int zOrder) {
        if (DBG) {
            Slogf.d(TAG, "Updating zOrder for decor %s to the new z order %d", this, zOrder);
        }
        mZOrder = zOrder;
        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();
        t.setLayer(viewSurface, mZOrder)
                .show(viewSurface);
    }

    @ShellMainThread
    @Override
    public void setView(SurfaceControl.Transaction t, View view) {
        // TODO(b/388083112): Implement setView call
        throw new IllegalArgumentException("Not implemented Yet");
    }

    @ShellMainThread
    @Override
    public void setVisibility(SurfaceControl.Transaction t, boolean isVisible) {
        if (DBG) {
            Slogf.d(TAG, "Updating Decor Visibility for decor %s to %s", this, isVisible);
        }

        SurfaceControl viewSurface = mViewHost.getSurfacePackage().getSurfaceControl();
        t.setVisibility(viewSurface, isVisible);
    }

    private WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(mBounds.width(),
                mBounds.height(), TYPE_APPLICATION, FLAG_NOT_FOCUSABLE | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSPARENT);
        lp.setTitle("AutoDecor");
        lp.setTrustedOverlay();
        return lp;
    }

    @Override
    public String toString() {
        return "AutoDecor(" + mView + ",\n" + mZOrder + ",\n" + mBounds + ",\n" + ")";
    }
}
