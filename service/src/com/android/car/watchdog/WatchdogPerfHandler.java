/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.watchdog;

import static android.car.watchdog.CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO;

import android.annotation.NonNull;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IResourceOveruseListener;
import android.car.watchdog.PackageKillableState;
import android.car.watchdog.ResourceOveruseConfiguration;
import android.car.watchdog.ResourceOveruseStats;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Handles system resource performance monitoring module.
 */
public final class WatchdogPerfHandler {
    private static final String TAG = CarLog.tagFor(CarWatchdogService.class);

    private final boolean mIsDebugEnabled;
    private final Object mLock = new Object();
    /*
     * Cache of added resource overuse listeners by uid.
     */
    @GuardedBy("mLock")
    private final SparseArray<ResourceOveruseListenerInfo> mOveruseListenerInfosByUid =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<ResourceOveruseListenerInfo> mOveruseSystemListenerInfosByUid =
            new SparseArray<>();

    public WatchdogPerfHandler(boolean isDebugEnabled) {
        mIsDebugEnabled = isDebugEnabled;
    }

    /** Initializes the handler. */
    public void init() {
        /**
         * TODO(b/170741935): Read the current day's I/O overuse stats from database and push them
         * to the daemon.
         */
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "WatchdogPerfHandler is initialized");
        }
    }

    /** Dumps its state. */
    public void dump(IndentingPrintWriter writer) {
        /**
         * TODO(b/170741935): Implement this method.
         */
    }

    /** Returns resource overuse stats for the calling package. */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // TODO(b/170741935): Implement this method.
        return new ResourceOveruseStats.Builder("",
                UserHandle.getUserHandleForUid(Binder.getCallingUid())).build();
    }

    /** Returns resource overuse stats for all packages. */
    @NonNull
    public List<ResourceOveruseStats> getAllResourceOveruseStats(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.MinimumStatsFlag int minimumStatsFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    /** Returns resource overuse stats for the specified user package. */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName, @NonNull UserHandle userHandle,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @CarWatchdogManager.StatsPeriod int maxStatsPeriod) {
        Objects.requireNonNull(packageName, "Package name must be non-null");
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Preconditions.checkArgument((maxStatsPeriod > 0),
                "Must provide valid maximum stats period");
        // TODO(b/170741935): Implement this method.
        return new ResourceOveruseStats.Builder("", userHandle).build();
    }

    /** Adds the resource overuse listener. */
    public void addResourceOveruseListener(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        synchronized (mLock) {
            addResourceOveruseListenerLocked(resourceOveruseFlag, listener,
                    mOveruseListenerInfosByUid);
        }
    }

    /** Removes the previously added resource overuse listener. */
    public void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        synchronized (mLock) {
            removeResourceOveruseListenerLocked(listener, mOveruseListenerInfosByUid);
        }
    }

    /** Adds the resource overuse system listener. */
    public void addResourceOveruseListenerForSystem(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        synchronized (mLock) {
            addResourceOveruseListenerLocked(resourceOveruseFlag, listener,
                    mOveruseSystemListenerInfosByUid);
        }
    }

    /** Removes the previously added resource overuse system listener. */
    public void removeResourceOveruseListenerForSystem(@NonNull IResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        synchronized (mLock) {
            removeResourceOveruseListenerLocked(listener, mOveruseSystemListenerInfosByUid);
        }
    }

    /** Sets whether or not a package is killable on resource overuse. */
    public void setKillablePackageAsUser(String packageName, UserHandle userHandle,
            boolean isKillable) {
        Objects.requireNonNull(packageName, "Package name must be non-null");
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        /*
         * TODO(b/170741935): Add/remove the package from the user do-no-kill list.
         *  If the {@code userHandle == UserHandle.ALL}, update the settings for all users.
         */
    }

    /** Returns the list of package killable states on resource overuse for the user. */
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle userHandle) {
        Objects.requireNonNull(userHandle, "User handle must be non-null");
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    /** Sets the given resource overuse configurations. */
    public void setResourceOveruseConfigurations(
            List<ResourceOveruseConfiguration> configurations,
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        Objects.requireNonNull(configurations, "Configurations must be non-null");
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        Set<Integer> seenComponentTypes = new ArraySet<>();
        for (ResourceOveruseConfiguration config : configurations) {
            int componentType = config.getComponentType();
            if (seenComponentTypes.contains(componentType)) {
                throw new IllegalArgumentException(
                        "Cannot provide duplicate configurations for the same component type");
            }
            if ((resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) != 0
                    && config.getIoOveruseConfiguration() == null) {
                throw new IllegalArgumentException("Must provide I/O overuse configuration");
            }
            seenComponentTypes.add(config.getComponentType());
        }
        // TODO(b/170741935): Implement this method.
    }

    /** Returns the available resource overuse configurations. */
    @NonNull
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag) {
        Preconditions.checkArgument((resourceOveruseFlag > 0),
                "Must provide valid resource overuse flag");
        // TODO(b/170741935): Implement this method.
        return new ArrayList<>();
    }

    private void addResourceOveruseListenerLocked(
            @CarWatchdogManager.ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull IResourceOveruseListener listener,
            SparseArray<ResourceOveruseListenerInfo> listenerInfosByUid) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isListenerForSystem = listenerInfosByUid == mOveruseSystemListenerInfosByUid;
        String listenerType = isListenerForSystem ? "resource overuse listener for system" :
                "resource overuse listener";

        ResourceOveruseListenerInfo existingListenerInfo = listenerInfosByUid.get(callingUid, null);
        if (existingListenerInfo != null) {
            IBinder binder = listener.asBinder();
            if (existingListenerInfo.listener.asBinder() == binder) {
                throw new IllegalStateException(
                        "Cannot add " + listenerType + " as it is already added");
            }
        }

        ResourceOveruseListenerInfo listenerInfo = new ResourceOveruseListenerInfo(listener,
                resourceOveruseFlag, callingPid, callingUid, isListenerForSystem);
        try {
            listenerInfo.linkToDeath();
        } catch (RemoteException e) {
            Slogf.w(TAG, "Cannot add %s: linkToDeath to listener failed", listenerType);
            return;
        }

        if (existingListenerInfo != null) {
            Slogf.w(TAG, "Overwriting existing %s: pid %d, uid: %d", listenerType,
                    existingListenerInfo.pid, existingListenerInfo.uid);
            existingListenerInfo.unlinkToDeath();
        }


        listenerInfosByUid.put(callingUid, listenerInfo);
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is added", listenerType,
                    callingPid, callingUid);
        }
    }

    private void removeResourceOveruseListenerLocked(@NonNull IResourceOveruseListener listener,
            SparseArray<ResourceOveruseListenerInfo> listenerInfosByUid) {
        int callingUid = Binder.getCallingUid();

        String listenerType = listenerInfosByUid == mOveruseSystemListenerInfosByUid
                ? "resource overuse system listener" : "resource overuse listener";

        ResourceOveruseListenerInfo listenerInfo = listenerInfosByUid.get(callingUid, null);
        if (listenerInfo == null || listenerInfo.listener != listener) {
            Slogf.w(TAG, "Cannot remove the %s: it has not been registered before", listenerType);
            return;
        }
        listenerInfo.unlinkToDeath();
        listenerInfosByUid.remove(callingUid);
        if (mIsDebugEnabled) {
            Slogf.d(TAG, "The %s (pid: %d, uid: %d) is removed", listenerType, listenerInfo.pid,
                    listenerInfo.uid);
        }
    }

    private void onResourceOveruseListenerDeath(int uid, boolean isListenerForSystem) {
        synchronized (mLock) {
            if (isListenerForSystem) {
                mOveruseSystemListenerInfosByUid.remove(uid);
            } else {
                mOveruseListenerInfosByUid.remove(uid);
            }
        }
    }

    private final class ResourceOveruseListenerInfo implements IBinder.DeathRecipient {
        public final IResourceOveruseListener listener;
        public final @CarWatchdogManager.ResourceOveruseFlag int flag;
        public final int pid;
        public final int uid;
        public final boolean isListenerForSystem;

        ResourceOveruseListenerInfo(IResourceOveruseListener listener,
                @CarWatchdogManager.ResourceOveruseFlag int flag, int pid, int uid,
                boolean isListenerForSystem) {
            this.listener = listener;
            this.flag = flag;
            this.pid = pid;
            this.uid = uid;
            this.isListenerForSystem = isListenerForSystem;
        }

        @Override
        public void binderDied() {
            Slogf.w(TAG, "Resource overuse listener%s (pid: %d) died",
                    isListenerForSystem ? " for system" : "", pid);
            onResourceOveruseListenerDeath(uid, isListenerForSystem);
            unlinkToDeath();
        }

        private void linkToDeath() throws RemoteException {
            listener.asBinder().linkToDeath(this, 0);
        }

        private void unlinkToDeath() {
            listener.asBinder().unlinkToDeath(this, 0);
        }
    }
}
