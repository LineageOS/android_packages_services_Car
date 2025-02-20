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

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.window.SurfaceSyncGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.util.HashMap;
import java.util.Map;

/**
 * A class for managing a set of atomic changes to multiple {@link AutoDecor} instances.
 * This class uses a {@link SurfaceSyncGroup} to ensure that all changes are applied
 * synchronously.
 */
public class AutoSurfaceTransaction {
    private static final String TAG = "AutoSurfaceTransaction";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private SurfaceSyncGroup mSurfaceSyncGroup;

    private final String mTransactionName;

    private final HashMap<AutoDecor, AutoDecorPendingChanges> mPendingAutoDecors = new HashMap<>();

    // Same AutoSurfaceTransaction can be reused after apply. The counter is to update the
    // TransactionName for debugging and tracking.
    private int mCounter = 1;
    private SurfaceControl.Transaction mTransaction;

    /**
     * Constructs a new AutoSurfaceTransaction with the given name.
     * @param transactionName The name of the transaction.
     */
    public AutoSurfaceTransaction(String transactionName) {
        this(transactionName, new SurfaceSyncGroup(transactionName),
                new SurfaceControl.Transaction());
    }

    @VisibleForTesting
    AutoSurfaceTransaction(String transactionName, SurfaceSyncGroup surfaceSyncGroup,
            SurfaceControl.Transaction transaction) {
        mTransactionName = transactionName;
        mSurfaceSyncGroup = surfaceSyncGroup;
        mTransaction = transaction;
    }

    private String getTransactionName() {
        return mTransactionName + "-" + mCounter++;
    }

    /**
     * Applies all pending changes in this transaction. This method synchronizes all the changes
     * using a {@link SurfaceSyncGroup} and updates the state of all affected {@link AutoDecor}s.
     */
    public void apply() {
        mSurfaceSyncGroup.addTransaction(mTransaction);
        mSurfaceSyncGroup.markSyncReady();
        // Update all the Decors state
        for (Map.Entry<AutoDecor, AutoDecorPendingChanges> entry : mPendingAutoDecors.entrySet()) {
            AutoDecor decor = entry.getKey();
            AutoDecorPendingChanges decorPendingChanges = entry.getValue();
            decorPendingChanges.applyChanges(decor);
        }

        mPendingAutoDecors.clear();
        mSurfaceSyncGroup = new SurfaceSyncGroup(getTransactionName());
        mTransaction = new SurfaceControl.Transaction();
    }

    /**
     * Sets the bounds of an {@link AutoDecor}. This method updates the layout parameters of the
     * decor's view and sets the pending bounds for the {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param bounds The new bounds.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setBounds(AutoDecor autoDecor, Rect bounds) {
        if (DBG) {
            Slogf.d(TAG, "Updating bounds for decor %s to the new bounds %s", autoDecor, bounds);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(bounds.width(),
                bounds.height(), TYPE_APPLICATION, FLAG_NOT_FOCUSABLE | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSPARENT);
        lp.setTitle(autoDecor.getName());
        lp.setTrustedOverlay();

        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControlViewHost.SurfacePackage surfacePackage = viewHost.getSurfacePackage();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        mTransaction.setPosition(surfaceControl, bounds.left, bounds.top);
        mSurfaceSyncGroup.add(surfacePackage, () -> {
            viewHost.relayout(lp);
        });
        if (!mPendingAutoDecors.containsKey(autoDecor)) {
            mPendingAutoDecors.put(autoDecor, new AutoDecorPendingChanges(autoDecor));
        }
        mPendingAutoDecors.get(autoDecor).setPendingBounds(bounds);
        return this;
    }

    /**
     * Sets the z-order of an {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param zOrder The new z-order.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setZOrder(AutoDecor autoDecor, int zOrder) {
        if (DBG) {
            Slogf.d(TAG, "Updating zOrder for decor %s to the new z order %d", autoDecor, zOrder);
        }

        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        mTransaction.setLayer(surfaceControl, zOrder);
        if (!mPendingAutoDecors.containsKey(autoDecor)) {
            mPendingAutoDecors.put(autoDecor, new AutoDecorPendingChanges(autoDecor));
        }
        mPendingAutoDecors.get(autoDecor).setPendingZOrder(zOrder);

        return this;
    }

    /**
     * Sets the visibility of an {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param isVisible The new visibility.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setVisibility(AutoDecor autoDecor, boolean isVisible) {
        if (DBG) {
            Slogf.d(TAG, "Updating Decor Visibility for decor %s to %s", this, isVisible);
        }

        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        mTransaction.setVisibility(surfaceControl, isVisible);
        if (!mPendingAutoDecors.containsKey(autoDecor)) {
            mPendingAutoDecors.put(autoDecor, new AutoDecorPendingChanges(autoDecor));
        }
        mPendingAutoDecors.get(autoDecor).setPendingIsVisible(isVisible);
        return this;
    }

    private static class AutoDecorPendingChanges {
        private int mPendingZOrder;
        private Rect mPendingBounds;
        private boolean mPendingIsVisible;

        public void setPendingZOrder(int pendingZOrder) {
            mPendingZOrder = pendingZOrder;
        }

        public void setPendingBounds(Rect pendingBounds) {
            mPendingBounds = pendingBounds;
        }

        public void setPendingIsVisible(boolean pendingIsVisible) {
            mPendingIsVisible = pendingIsVisible;
        }

        AutoDecorPendingChanges(AutoDecor decor) {
            mPendingZOrder = decor.getZOrder();
            mPendingBounds = decor.getBounds();
            mPendingIsVisible = decor.isVisible();
        }

        public void applyChanges(AutoDecor decor) {
            decor.updateVisibility(mPendingIsVisible);
            decor.updateBounds(mPendingBounds);
            decor.updateZOrder(mPendingZOrder);
        }
    }
}
