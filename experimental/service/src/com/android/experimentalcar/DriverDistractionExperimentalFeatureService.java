/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.experimentalcar;

import android.annotation.Nullable;
import android.car.experimental.DriverAwarenessEvent;
import android.car.experimental.DriverAwarenessSupplierConfig;
import android.car.experimental.DriverAwarenessSupplierService;
import android.car.experimental.IDriverAwarenessSupplier;
import android.car.experimental.IDriverAwarenessSupplierCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;

import com.android.car.CarServiceBase;
import com.android.car.Utils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;

/**
 * Driver Distraction Service for using the driver's awareness, the required awareness of the
 * driving environment to expose APIs for the driver's current distraction level.
 *
 * <p>Allows the registration of multiple {@link IDriverAwarenessSupplier} so that higher accuracy
 * signals can be used when possible, with a fallback to less accurate signals. The {@link
 * TouchDriverAwarenessSupplier} is always set to the fallback implementation - it is configured
 * to send change-events, so its data will not become stale.
 */
public final class DriverDistractionExperimentalFeatureService implements CarServiceBase {

    private static final String TAG = "CAR.DriverDistractionService";

    private static final float DEFAULT_AWARENESS_VALUE_FOR_LOG = 1.0f;
    private static final int MAX_DRIVER_AWARENESS_EVENT_LOG_COUNT = 50;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayDeque<Utils.TransitionLog> mDriverAwarenessTransitionLogs =
            new ArrayDeque<>();

    /**
     * All the active service connections.
     */
    @GuardedBy("mLock")
    private final List<ServiceConnection> mServiceConnections = new ArrayList<>();

    /**
     * The binder for each supplier.
     */
    @GuardedBy("mLock")
    private final Map<ComponentName, IDriverAwarenessSupplier> mSupplierBinders = new HashMap<>();

    /**
     * The configuration for each supplier.
     */
    @GuardedBy("mLock")
    private final Map<IDriverAwarenessSupplier, DriverAwarenessSupplierConfig> mSupplierConfigs =
            new HashMap<>();

    /**
     * List of driver awareness suppliers that can be used to understand the current driver
     * awareness level. Ordered from highest to lowest priority.
     */
    @GuardedBy("mLock")
    private final List<IDriverAwarenessSupplier> mPrioritizedDriverAwarenessSuppliers =
            new ArrayList<>();

    /**
     * Helper map for looking up the priority rank of a supplier by name. A higher integer value
     * represents a higher priority.
     */
    @GuardedBy("mLock")
    private final Map<IDriverAwarenessSupplier, Integer> mDriverAwarenessSupplierPriorities =
            new HashMap<>();

    /**
     * Comparator used to sort {@link #mDriverAwarenessSupplierPriorities}.
     */
    private final Comparator<IDriverAwarenessSupplier> mPrioritizedSuppliersComparator =
            (left, right) -> {
                int leftPri = mDriverAwarenessSupplierPriorities.get(left);
                int rightPri = mDriverAwarenessSupplierPriorities.get(right);
                // sort descending
                return rightPri - leftPri;
            };

    /**
     * Keep track of the most recent awareness event for each supplier for use when the data from
     * higher priority suppliers becomes stale. This is necessary in order to seamlessly handle
     * fallback scenarios when data from preferred providers becomes stale.
     */
    @GuardedBy("mLock")
    private final Map<IDriverAwarenessSupplier, DriverAwarenessEventWrapper>
            mCurrentAwarenessEventsMap =
            new HashMap<>();

    /**
     * The awareness event that is currently being used to determine the driver awareness level.
     *
     * <p>This is null until it is set by the first awareness supplier to send an event
     */
    @GuardedBy("mLock")
    @Nullable
    private DriverAwarenessEventWrapper mCurrentDriverAwareness;

    /**
     * Timer to alert when the current driver awareness event has become stale.
     */
    @GuardedBy("mLock")
    private ITimer mExpiredDriverAwarenessTimer;

    private final ITimeSource mTimeSource;
    private final Context mContext;

    /**
     * Create an instance of {@link DriverDistractionExperimentalFeatureService}.
     *
     * @param context    the context
     * @param timeSource the source that provides the current time
     * @param timer      the timer used for scheduling
     */
    DriverDistractionExperimentalFeatureService(
            Context context,
            ITimeSource timeSource,
            ITimer timer) {
        mContext = context;
        mTimeSource = timeSource;
        mExpiredDriverAwarenessTimer = timer;
    }

    @Override
    public void init() {
        // The touch supplier is an internal implementation, so it can be started initiated by its
        // constructor, unlike other suppliers
        ComponentName touchComponent = new ComponentName(mContext,
                TouchDriverAwarenessSupplier.class);
        TouchDriverAwarenessSupplier touchSupplier = new TouchDriverAwarenessSupplier();
        addDriverAwarenessSupplier(touchComponent, touchSupplier, /* priority= */ 0);
        touchSupplier.setCallback(new DriverAwarenessSupplierCallback(touchComponent));
        touchSupplier.onReady();

        String[] preferredDriverAwarenessSuppliers = mContext.getResources().getStringArray(
                R.array.preferredDriverAwarenessSuppliers);
        for (int i = 0; i < preferredDriverAwarenessSuppliers.length; i++) {
            String supplierStringName = preferredDriverAwarenessSuppliers[i];
            ComponentName externalComponent = ComponentName.unflattenFromString(supplierStringName);
            // the touch supplier has priority 0 and preferred suppliers are higher based on order
            int priority = i + 1;
            bindDriverAwarenessSupplierService(externalComponent, priority);
        }
    }

    @Override
    public void release() {
        logd("release");
        synchronized (mLock) {
            for (ServiceConnection serviceConnection : mServiceConnections) {
                mContext.unbindService(serviceConnection);
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*DriverDistractionExperimentalFeatureService*");
        writer.println("Prioritized Driver Awareness Suppliers (highest to lowest priority):");
        synchronized (mLock) {
            for (int i = 0; i < mPrioritizedDriverAwarenessSuppliers.size(); i++) {
                writer.println(
                        String.format("  %d: %s", i, mPrioritizedDriverAwarenessSuppliers.get(
                                i).getClass().getName()));
            }
            writer.println("Current Driver Awareness:");
            writer.println("  Value: "
                    + (mCurrentDriverAwareness == null ? "unknown"
                    : mCurrentDriverAwareness.mAwarenessEvent.getAwarenessValue()));
            writer.println("  Supplier: " + (mCurrentDriverAwareness == null ? "unknown"
                    : mCurrentDriverAwareness.mSupplier.getClass().getSimpleName()));
            writer.println("  Timestamp (since boot): "
                    + (mCurrentDriverAwareness == null ? "unknown"
                    : mCurrentDriverAwareness.mAwarenessEvent.getTimeStamp()));
            writer.println("Driver Awareness change log:");
            for (Utils.TransitionLog log : mDriverAwarenessTransitionLogs) {
                writer.println(log);
            }
        }
    }

    /**
     * Bind to a {@link DriverAwarenessSupplierService} by its component name.
     *
     * @param componentName the name of the {@link DriverAwarenessSupplierService} to bind to.
     * @param priority      the priority rank of this supplier
     */
    private void bindDriverAwarenessSupplierService(ComponentName componentName, int priority) {
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ServiceConnection connection = new DriverAwarenessServiceConnection(priority);
        synchronized (mLock) {
            mServiceConnections.add(connection);
        }
        if (!mContext.bindServiceAsUser(intent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.SYSTEM)) {
            Log.e(TAG, "Unable to bind with intent: " + intent);
            // TODO(b/146471650) attempt to rebind
        }
    }

    @VisibleForTesting
    void handleDriverAwarenessEvent(DriverAwarenessEventWrapper awarenessEventWrapper) {
        synchronized (mLock) {
            handleDriverAwarenessEventLocked(awarenessEventWrapper);
        }
    }

    /**
     * Handle the driver awareness event by:
     * <ul>
     *     <li>Cache the driver awareness event for its supplier</li>
     *     <li>Update the current awareness value</li>
     *     <li>Register to refresh the awareness value again when the new current expires</li>
     * </ul>
     *
     * @param awarenessEventWrapper the driver awareness event that has occurred
     */
    @GuardedBy("mLock")
    private void handleDriverAwarenessEventLocked(
            DriverAwarenessEventWrapper awarenessEventWrapper) {
        // update the current awareness event for the supplier, checking that it is the newest event
        IDriverAwarenessSupplier supplier = awarenessEventWrapper.mSupplier;
        long timestamp = awarenessEventWrapper.mAwarenessEvent.getTimeStamp();
        if (!mCurrentAwarenessEventsMap.containsKey(supplier)
                || mCurrentAwarenessEventsMap.get(supplier).mAwarenessEvent.getTimeStamp()
                < timestamp) {
            mCurrentAwarenessEventsMap.put(awarenessEventWrapper.mSupplier, awarenessEventWrapper);
        }

        int oldSupplierPriority = mDriverAwarenessSupplierPriorities.get(supplier);
        float oldAwarenessValue = DEFAULT_AWARENESS_VALUE_FOR_LOG;
        if (mCurrentDriverAwareness != null) {
            oldAwarenessValue = mCurrentDriverAwareness.mAwarenessEvent.getAwarenessValue();
        }

        updateCurrentAwarenessValueLocked();

        int newSupplierPriority = mDriverAwarenessSupplierPriorities.get(
                mCurrentDriverAwareness.mSupplier);
        if (mSupplierConfigs.get(mCurrentDriverAwareness.mSupplier).getMaxStalenessMillis()
                != DriverAwarenessSupplierService.NO_STALENESS
                && newSupplierPriority >= oldSupplierPriority) {
            // only reschedule an expiration if this is for a supplier that is the same or higher
            // priority than the old value. If there is a higher priority supplier with non-stale
            // data, then mCurrentDriverAwareness won't change even though we received a new event.
            scheduleExpirationTimerLocked();
        }

        if (oldAwarenessValue != mCurrentDriverAwareness.mAwarenessEvent.getAwarenessValue()) {
            logd("Driver awareness updated: "
                    + mCurrentDriverAwareness.mAwarenessEvent.getAwarenessValue());
            addDriverAwarenessTransitionLogLocked(oldAwarenessValue,
                    awarenessEventWrapper.mAwarenessEvent.getAwarenessValue(),
                    awarenessEventWrapper.mSupplier.getClass().getSimpleName());
        }
    }

    /**
     * Get the current awareness value.
     */
    @VisibleForTesting
    DriverAwarenessEventWrapper getCurrentDriverAwareness() {
        return mCurrentDriverAwareness;
    }

    /**
     * Set the drier awareness suppliers. Allows circumventing the {@link #init()} logic.
     */
    @VisibleForTesting
    void setDriverAwarenessSuppliers(
            List<Pair<IDriverAwarenessSupplier, DriverAwarenessSupplierConfig>> suppliers) {
        mPrioritizedDriverAwarenessSuppliers.clear();
        mDriverAwarenessSupplierPriorities.clear();
        for (int i = 0; i < suppliers.size(); i++) {
            Pair<IDriverAwarenessSupplier, DriverAwarenessSupplierConfig> pair = suppliers.get(i);
            mSupplierConfigs.put(pair.first, pair.second);
            mDriverAwarenessSupplierPriorities.put(pair.first, i);
            mPrioritizedDriverAwarenessSuppliers.add(pair.first);
        }
        mPrioritizedDriverAwarenessSuppliers.sort(mPrioritizedSuppliersComparator);
    }

    /**
     * Internally register the supplier with the specified priority.
     */
    private void addDriverAwarenessSupplier(
            ComponentName componentName,
            IDriverAwarenessSupplier awarenessSupplier,
            int priority) {
        synchronized (mLock) {
            mSupplierBinders.put(componentName, awarenessSupplier);
            mDriverAwarenessSupplierPriorities.put(awarenessSupplier, priority);
            mPrioritizedDriverAwarenessSuppliers.add(awarenessSupplier);
            mPrioritizedDriverAwarenessSuppliers.sort(mPrioritizedSuppliersComparator);
        }
    }

    /**
     * Remove references to a supplier.
     */
    private void removeDriverAwarenessSupplier(ComponentName componentName) {
        synchronized (mLock) {
            IDriverAwarenessSupplier supplier = mSupplierBinders.get(componentName);
            mSupplierBinders.remove(componentName);
            mDriverAwarenessSupplierPriorities.remove(supplier);
            mPrioritizedDriverAwarenessSuppliers.remove(supplier);
        }
    }

    /**
     * Update {@link #mCurrentDriverAwareness} based on the current driver awareness events for each
     * supplier.
     */
    @GuardedBy("mLock")
    private void updateCurrentAwarenessValueLocked() {
        for (IDriverAwarenessSupplier supplier : mPrioritizedDriverAwarenessSuppliers) {
            long supplierMaxStaleness = mSupplierConfigs.get(supplier).getMaxStalenessMillis();
            DriverAwarenessEventWrapper eventForSupplier = mCurrentAwarenessEventsMap.get(supplier);
            if (eventForSupplier == null) {
                continue;
            }
            if (supplierMaxStaleness == DriverAwarenessSupplierService.NO_STALENESS) {
                // this supplier can't be stale, so use its information
                mCurrentDriverAwareness = eventForSupplier;
                return;
            }

            long oldestFreshTimestamp = mTimeSource.elapsedRealtime() - supplierMaxStaleness;
            if (eventForSupplier.mAwarenessEvent.getTimeStamp() > oldestFreshTimestamp) {
                // value is still fresh, so use it
                mCurrentDriverAwareness = eventForSupplier;
                return;
            }
        }

        if (mCurrentDriverAwareness == null) {
            // There must always at least be a fallback supplier with NO_STALENESS configuration.
            // Since we control this configuration, getting this exception represents a developer
            // error in initialization.
            throw new IllegalStateException(
                    "Unable to determine the current driver awareness value");
        }
    }

    /**
     * Sets a timer to update the refresh the awareness value once the current value has become
     * stale.
     */
    @GuardedBy("mLock")
    private void scheduleExpirationTimerLocked() {
        // reschedule the current awareness expiration task
        mExpiredDriverAwarenessTimer.reset();
        long delay = mCurrentDriverAwareness.mAwarenessEvent.getTimeStamp()
                - mTimeSource.elapsedRealtime()
                + mCurrentDriverAwareness.mMaxStaleness;
        if (delay < 0) {
            // somehow the event is already stale
            synchronized (mLock) {
                updateCurrentAwarenessValueLocked();
            }
            return;
        }
        mExpiredDriverAwarenessTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                logd("Driver awareness has become stale. Selecting new awareness level.");
                synchronized (mLock) {
                    updateCurrentAwarenessValueLocked();
                }
            }
        }, delay);

        logd(String.format(
                "Current awareness value is stale after %sms and is scheduled to expire in %sms",
                mCurrentDriverAwareness.mMaxStaleness, delay));
    }

    /**
     * Add the driver awareness state change to the transition log.
     *
     * @param oldValue     the old driver awareness value
     * @param newValue     the new driver awareness value
     * @param supplierName the name of the supplier that is responsible for the new value
     */
    @GuardedBy("mLock")
    private void addDriverAwarenessTransitionLogLocked(float oldValue, float newValue,
            String supplierName) {
        if (mDriverAwarenessTransitionLogs.size() >= MAX_DRIVER_AWARENESS_EVENT_LOG_COUNT) {
            mDriverAwarenessTransitionLogs.remove();
        }

        Utils.TransitionLog tLog = new Utils.TransitionLog(TAG, (int) (oldValue * 100),
                (int) (newValue * 100), System.currentTimeMillis(),
                "Driver awareness updated by " + supplierName);
        mDriverAwarenessTransitionLogs.add(tLog);
    }

    private static void logd(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }

    /**
     * The service connection between this distraction service and a {@link
     * DriverAwarenessSupplierService}, communicated through {@link IDriverAwarenessSupplier}.
     */
    private class DriverAwarenessServiceConnection implements ServiceConnection {

        final int mPriority;

        /**
         * Create an instance of {@link DriverAwarenessServiceConnection}.
         *
         * @param priority the priority of the {@link DriverAwarenessSupplierService} that this
         *                 connection is for
         */
        DriverAwarenessServiceConnection(int priority) {
            mPriority = priority;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            logd("onServiceConnected, name: " + name + ", binder: " + binder);
            IDriverAwarenessSupplier service = IDriverAwarenessSupplier.Stub.asInterface(
                    binder);
            addDriverAwarenessSupplier(name, service, mPriority);
            try {
                service.setCallback(new DriverAwarenessSupplierCallback(name));
                service.onReady();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to call onReady on supplier", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            logd("onServiceDisconnected, name: " + name);
            removeDriverAwarenessSupplier(name);
            // TODO(b/146471650) rebind to driver awareness suppliers on service disconnect
        }
    }

    /**
     * Driver awareness listener that keeps a references to some attributes of the supplier.
     */
    private class DriverAwarenessSupplierCallback extends IDriverAwarenessSupplierCallback.Stub {

        private final ComponentName mComponentName;

        /**
         * Construct an instance  of {@link DriverAwarenessSupplierCallback}.
         *
         * @param componentName the driver awareness supplier for this listener
         */
        DriverAwarenessSupplierCallback(ComponentName componentName) {
            mComponentName = componentName;
        }

        @Override
        public void onDriverAwarenessUpdated(DriverAwarenessEvent event) {
            IDriverAwarenessSupplier supplier;
            long maxStaleness;
            synchronized (mLock) {
                supplier = mSupplierBinders.get(mComponentName);
                maxStaleness = mSupplierConfigs.get(supplier).getMaxStalenessMillis();
            }
            if (supplier == null) {
                // this should never happen. Initialization process would not be correct.
                throw new IllegalStateException(
                        "No supplier registered for component " + mComponentName);
            }
            logd(String.format("Driver awareness updated for %s: %s",
                    supplier.getClass().getSimpleName(), event));
            handleDriverAwarenessEvent(
                    new DriverAwarenessEventWrapper(event, supplier, maxStaleness));
        }

        @Override
        public void onConfigLoaded(DriverAwarenessSupplierConfig config) throws RemoteException {
            synchronized (mLock) {
                mSupplierConfigs.put(mSupplierBinders.get(mComponentName), config);
            }
        }
    }

    /**
     * Wrapper for {@link DriverAwarenessEvent} that includes some information from the supplier
     * that emitted the event.
     */
    @VisibleForTesting
    static class DriverAwarenessEventWrapper {
        final DriverAwarenessEvent mAwarenessEvent;
        final IDriverAwarenessSupplier mSupplier;
        final long mMaxStaleness;

        /**
         * Construct an instance of {@link DriverAwarenessEventWrapper}.
         *
         * @param awarenessEvent the driver awareness event being wrapped
         * @param supplier       the driver awareness supplier for this listener
         * @param maxStaleness   the max staleness of the supplier that emitted this event (included
         *                       to avoid making a binder call)
         */
        DriverAwarenessEventWrapper(
                DriverAwarenessEvent awarenessEvent,
                IDriverAwarenessSupplier supplier,
                long maxStaleness) {
            mAwarenessEvent = awarenessEvent;
            mSupplier = supplier;
            mMaxStaleness = maxStaleness;
        }

        @Override
        public String toString() {
            return String.format(
                    "DriverAwarenessEventWrapper{mAwarenessChangeEvent=%s, mSupplier=%s, "
                            + "mMaxStaleness=%s}",
                    mAwarenessEvent, mSupplier, mMaxStaleness);
        }
    }
}
