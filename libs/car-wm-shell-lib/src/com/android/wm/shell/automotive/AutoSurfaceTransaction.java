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

import android.annotation.NonNull;
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
import java.util.Objects;

/**
 * A class for managing a set of atomic changes to multiple {@link AutoDecor} instances.
 * This class uses a {@link SurfaceSyncGroup} to ensure that all changes are applied
 * synchronously.
 */
public class AutoSurfaceTransaction {
    private static final String TAG = "AutoSurfaceTransaction";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final AutoTaskRepository mAutoTaskRepository;

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
    AutoSurfaceTransaction(String transactionName, AutoTaskRepository autoTaskRepository) {
        this(transactionName, new SurfaceSyncGroup(transactionName),
                new SurfaceControl.Transaction(), autoTaskRepository);
    }

    @VisibleForTesting
    AutoSurfaceTransaction(String transactionName, SurfaceSyncGroup surfaceSyncGroup,
            SurfaceControl.Transaction transaction, AutoTaskRepository autoTaskRepository) {
        mTransactionName = transactionName;
        mSurfaceSyncGroup = surfaceSyncGroup;
        mTransaction = transaction;
        mAutoTaskRepository = autoTaskRepository;
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
     * Sets the task surface visibility.
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param isVisible The task visibility
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfaceVisibility(int taskId, boolean isVisible) {
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setVisibility(surfaceControl, isVisible);
        return this;
    }

    /**
     * Sets the task surface alpha.
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param alpha The task surface alpha
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfaceAlpha(int taskId, float alpha) {
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setAlpha(surfaceControl, alpha);
        return this;
    }

    /**
     * Sets the task surface layer.
     *
     * This API should only be used for animation during transition. The task surface final layer
     * is determined based on {@link AutoTaskStackTransaction}
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param layer The task surface layer
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfaceTransientLayer(int taskId, int layer) {
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setLayer(surfaceControl, layer);
        return this;
    }

    /**
     * Sets the task surface position.
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param x the X position
     * @param y the Y position
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfacePosition(int taskId, float x, float y) {
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setPosition(surfaceControl, x, y);
        return this;
    }

    /**
     * Sets the task surface corner radius.
     *
     * <p>The API should not be used on default launch root task as there is no way to update apps
     * that task has corner radius.
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param cornerRadius the corner radius
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfaceCornerRadius(int taskId, float cornerRadius) {
        // TODO(b/388083112): Add a check that only task not launched in default launch root task
        //  are using it as feature is not properly supported at this point.
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setCornerRadius(surfaceControl, cornerRadius);
        return this;
    }

    /**
     * Sets the task surface crop
     *
     * @param taskId The taskId whose surface needs to be updated.
     * @param cropBounds Updated bounds.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setTaskSurfaceCrop(int taskId, @NonNull Rect cropBounds) {
        Objects.requireNonNull(cropBounds);
        SurfaceControl surfaceControl = mAutoTaskRepository.getSurfaceControl(taskId);
        mTransaction.setCrop(surfaceControl, cropBounds);
        return this;
    }

    /**
     * Sets the alpha of an {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param alpha The decor surface alpha.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setAlpha(@NonNull AutoDecor autoDecor,
            float alpha) {
        Objects.requireNonNull(autoDecor);
        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        mTransaction.setAlpha(surfaceControl, alpha);
        return this;
    }

    /**
     * Sets the z-order of an {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param cornerRadius The corner radius.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setCornerRadius(@NonNull AutoDecor autoDecor,
            float cornerRadius) {
        Objects.requireNonNull(autoDecor);
        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        // Crop is required for setCornerRadius API to work.
        mTransaction.setCrop(surfaceControl,
                new Rect(0, 0, autoDecor.getBounds().width(), autoDecor.getBounds().height()));
        mTransaction.setCornerRadius(surfaceControl, cornerRadius);
        return this;
    }

    /**
     * Sets the Auto Decor crop
     *
     * @param autoDecor The {@link AutoDecor} to update.
     * @param cropBounds Updated bounds.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setCrop(@NonNull AutoDecor autoDecor, @NonNull Rect cropBounds) {
        Objects.requireNonNull(autoDecor);
        Objects.requireNonNull(cropBounds);
        SurfaceControlViewHost viewHost = autoDecor.getViewHost();
        SurfaceControl surfaceControl = viewHost.getSurfacePackage().getSurfaceControl();
        mTransaction.setCrop(surfaceControl, cropBounds);
        return this;
    }

    /**
     * Sets the bounds of an {@link AutoDecor}. This method updates the layout parameters of the
     * decor's view and sets the pending bounds for the {@link AutoDecor}.
     * @param autoDecor The {@link AutoDecor} to update.
     * @param bounds The new bounds.
     * @return This {@link AutoSurfaceTransaction} instance for chaining.
     */
    public AutoSurfaceTransaction setBounds(@NonNull AutoDecor autoDecor, @NonNull Rect bounds) {
        Objects.requireNonNull(autoDecor);
        Objects.requireNonNull(bounds);
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
    public AutoSurfaceTransaction setZOrder(@NonNull AutoDecor autoDecor, int zOrder) {
        Objects.requireNonNull(autoDecor);
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
    public AutoSurfaceTransaction setVisibility(@NonNull AutoDecor autoDecor, boolean isVisible) {
        Objects.requireNonNull(autoDecor);
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
